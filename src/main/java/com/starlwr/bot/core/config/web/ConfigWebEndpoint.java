package com.starlwr.bot.core.config.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.ConfigException;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.config.service.ConfigActionService;
import com.starlwr.bot.core.config.service.ConfigEditService;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * 配置中心请求处理器
 */
@Slf4j
public class ConfigWebEndpoint implements DisposableBean {
    /**
     * 配置接口允许读取的最大请求字节数
     */
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    /**
     * 配置中心静态文件名集合
     */
    private static final Set<String> ASSETS = Set.of("style.css", "app.js", "engine.js", "controls.js", "regex-worker.js", "icon.png", "logo.png");

    /**
     * 配置元数据注册表
     */
    private final ConfigMetaRegistry registry;

    /**
     * 配置中心编辑服务
     */
    private final ConfigEditService editService;

    /**
     * 配置中心动作服务
     */
    private final ConfigActionService actions;

    /**
     * 本次运行使用的访问密码哈希
     */
    private final String passwordHash;

    /**
     * 本次运行使用的随机访问密码
     */
    private final String randomPassword;

    /**
     * 是否在访问登录页时再次输出随机密码
     */
    private final boolean printPasswordOnPage;

    /**
     * 登录页显示的随机密码获取提示或密码重置提示
     */
    private final String loginNotice;

    /**
     * 当前进程用于缓存成功校验结果的随机盐
     */
    private final byte[] verificationSalt = new byte[32];

    /**
     * 已验证密码的进程内摘要，不保存固定密码原文
     */
    private volatile byte[] verifiedPassword;

    /**
     * 密码并发校验许可
     */
    private final Semaphore passwordChecks = new Semaphore(2);

    /**
     * 配置请求体
     */
    @FunctionalInterface
    public interface RequestBody {
        /**
         * 打开请求体输入流
         * @return HTTP 入口提供的请求体输入流
         */
        InputStream open() throws IOException;
    }

    /**
     * 配置 HTTP 响应
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Response {
        /**
         * HTTP 状态码
         */
        private final int status;

        /**
         * 响应内容类型
         */
        private final String contentType;

        /**
         * 响应体字节数组
         */
        private final byte[] body;

        /**
         * 额外响应头映射
         */
        private final Map<String, String> headers;

        /**
         * 比较两个响应，响应体根据数组引用比较
         * @param other 待比较的对象
         * @return 两个响应是否相等
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Response response && status == response.status && body == response.body
                    && Objects.equals(contentType, response.contentType) && Objects.equals(headers, response.headers);
        }

        /**
         * 根据响应字段计算哈希值
         * @return 响应的哈希值
         */
        @Override
        public int hashCode() {
            return Objects.hash(status, contentType, System.identityHashCode(body), headers);
        }

        /**
         * 生成响应摘要
         * @return 响应摘要
         */
        @Override
        public String toString() {
            return "Response[status=" + status + ", contentType=" + contentType + ", bodyLength=" + (body == null ? 0 : body.length)
                    + ", headers=" + headers + "]";
        }
    }

    public ConfigWebEndpoint(ConfigMetaRegistry registry, ConfigEditService editService, ConfigActionService actions, String token,
                             boolean randomPassword, boolean printPasswordOnPage, String loginNotice) {
        this.registry = registry;
        this.editService = editService;
        this.actions = actions;
        this.passwordHash = ConfigPasswordUtil.encode(token);
        this.randomPassword = randomPassword ? token : null;
        this.printPasswordOnPage = printPasswordOnPage;
        this.loginNotice = loginNotice;
        new SecureRandom().nextBytes(verificationSalt);
    }

    @Override
    public void destroy() throws IOException {
        actions.close();
    }

    /**
     * 业务启动完成后输出配置中心访问地址和密码提示
     * @param event 应用就绪事件
     */
    @Order(-20000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent(ApplicationReadyEvent event) {
        Environment environment = event.getApplicationContext().getEnvironment();
        printAccessInfo(environment.getProperty("server.address", "0.0.0.0"), environment.getProperty("local.server.port", Integer.class, 7827));
    }

    /**
     * 输出配置中心访问地址和密码提示
     * @param host 监听地址
     * @param port 实际监听端口
     */
    public void printAccessInfo(String host, int port) {
        String address = "0.0.0.0".equals(host) || "::".equals(host) ? "127.0.0.1" : host;
        if (address.contains(":") && !address.startsWith("[")) {
            address = "[" + address + "]";
        }
        log.info("配置中心: http://{}:{}/config", address, port);
        if (randomPassword == null && StringUtils.isNotEmpty(passwordHash) && ConfigPasswordUtil.isWeak(passwordHash)) {
            log.warn("配置中心访问密码强度较低, 建议使用更复杂的密码");
        }
        printRandomPassword();
    }

    /**
     * 处理配置请求，并将接口错误以 JSON 返回
     * @param method HTTP 方法
     * @param path 不含应用上下文的请求路径
     * @param authorization 访问凭据请求头
     * @param origin 来源请求头，可为空
     * @param host 当前请求主机
     * @param body 请求通过验证后用于打开请求体的对象
     * @return HTTP 响应
     */
    public Response handle(String method, String path, String authorization, String origin, String host, RequestBody body) {
        try {
            // 将 /config 重定向到 /config/
            if ("GET".equals(method) && "/config".equals(path)) {
                return new Response(302, "text/plain; charset=utf-8", new byte[0], Map.of("Location", "config/"));
            }

            // 先检查密码和请求来源，再处理配置接口
            if (path.startsWith("/config/api/")) {
                authorize(authorization, origin, host);
                JSONObject result;
                if ("GET".equals(method)) {
                    result = path.startsWith("/config/api/action-status/") ? actions.getStatus(path.substring("/config/api/action-status/".length())) : switch (path) {
                        case "/config/api/bootstrap" -> JSONObject.of("meta", registry.getMeta(), "diagnostics", registry.getDiagnostics(),
                                "springPaths", registry.getSpringBindings().values(), "accessPasswordPath", ConfigPasswordUtil.PASSWORD_PATH,
                                "beanActionsAvailable", actions.supportsBeanActions(), "document", editService.read());
                        case "/config/api/document" -> editService.read();
                        default -> throw new ConfigException(404, "接口不存在");
                    };
                } else if ("POST".equals(method)) {
                    // 验证通过后读取请求体，并限制读取大小
                    byte[] content;
                    try (InputStream input = body.open()) {
                        content = input.readNBytes(MAX_BYTES + 1);
                    }
                    if (content.length > MAX_BYTES) {
                        throw new ConfigException(413, "请求超过 16 MiB");
                    }
                    JSONObject request;
                    try {
                        request = JSON.parseObject(new String(content, StandardCharsets.UTF_8));
                    } catch (RuntimeException e) {
                        throw new ConfigException(400, "请求必须是 JSON 对象");
                    }
                    if (request == null) {
                        throw new ConfigException(400, "请求必须是 JSON 对象");
                    }
                    result = switch (path) {
                        case "/config/api/preview" -> editService.preview(request);
                        case "/config/api/save" -> editService.save(request);
                        case "/config/api/action" -> actions.execute(request);
                        default -> throw new ConfigException(404, "接口不存在");
                    };
                } else {
                    throw new ConfigException(405, "请求方法不支持");
                }
                return createJsonResponse(200, result);
            }

            // 页面和静态文件只接受 GET，且只提供列表中的文件
            if (!"GET".equals(method)) {
                throw new ConfigException(405, "请求方法不支持");
            }
            String resource;
            String type;
            if ("/config/".equals(path)) {
                resource = "/config-center/index.html";
                type = "text/html; charset=utf-8";
            } else if (path.startsWith("/config/assets/") && ASSETS.contains(path.substring("/config/assets/".length()))) {
                String name = path.substring("/config/assets/".length());
                resource = name.endsWith(".png") ? "/" + name : "/config-center/" + name;
                type = name.endsWith(".png") ? "image/png" : name.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
            } else {
                throw new ConfigException(404, "页面不存在");
            }

            // 读取页面文件，并在登录页显示对应的密码提示
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ConfigException(404, "页面资源不存在");
                }
                byte[] content = input.readAllBytes();
                if ("/config/".equals(path)) {
                    String html = new String(content, StandardCharsets.UTF_8);
                    String notice = "";
                    if (StringUtils.isNotBlank(loginNotice)) {
                        html = html.replace("id=\"starbot-password-notice\" hidden", "id=\"starbot-password-notice\"");
                        notice = loginNotice.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                    }
                    content = html.replace("{{password-notice}}", notice).getBytes(StandardCharsets.UTF_8);
                }

                // 仅在访问登录页时再次输出当前随机密码
                if (printPasswordOnPage && "/config/".equals(path)) {
                    printRandomPassword();
                }
                return new Response(200, type, content, Map.of());
            }
        } catch (ConfigException e) {
            return createJsonResponse(e.getStatus(), JSONObject.of("message", e.getMessage(), "details", e.getDetails()));
        } catch (SecurityException e) {
            return createJsonResponse(422, JSONObject.of("message", e.getMessage()));
        } catch (IOException e) {
            return createJsonResponse(500, JSONObject.of("message", "无法读写配置文件, 请检查文件权限"));
        } catch (RuntimeException e) {
            return createJsonResponse(500, JSONObject.of("message", "配置操作失败, 请检查配置内容"));
        }
    }

    /**
     * 向控制台输出本次运行使用的随机密码
     */
    private void printRandomPassword() {
        if (randomPassword != null) {
            System.out.println("配置中心访问密码: " + randomPassword);
        }
    }

    /**
     * 检查访问密码及请求来源
     * @param authorization 访问凭据请求头
     * @param origin 来源请求头，可为空
     * @param host 当前请求主机
     */
    private void authorize(String authorization, String origin, String host) {
        // 检查访问密码的格式和请求来源
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)
                || authorization.length() > prefix.length() + ConfigPasswordUtil.MAX_LENGTH) {
            throw new ConfigException(401, "访问密码不正确");
        }
        if (origin != null) {
            try {
                if (!URI.create(origin).getAuthority().equalsIgnoreCase(host)) {
                    throw new ConfigException(403, "不支持跨域访问");
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ConfigException(403, "请求来源无效");
            }
        }

        // 计算当前密码的摘要，检查是否已经通过验证
        String supplied = authorization.substring(prefix.length());
        byte[] digest;
        try {
            MessageDigest verifier = MessageDigest.getInstance("SHA-256");
            verifier.update(verificationSalt);
            digest = verifier.digest(supplied.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法校验配置中心访问密码", e);
        }

        // 未验证过的密码需重新校验，同时限制校验数量
        byte[] cached = verifiedPassword;
        if (cached == null || !MessageDigest.isEqual(cached, digest)) {
            if (!passwordChecks.tryAcquire()) {
                throw new ConfigException(429, "正在校验访问密码, 请稍后重试");
            }
            try {
                if (!ConfigPasswordUtil.matches(supplied, passwordHash)) {
                    throw new ConfigException(401, "访问密码不正确");
                }
                verifiedPassword = digest;
            } finally {
                passwordChecks.release();
            }
        }
    }

    /**
     * 生成保留 null 字段的 JSON 响应
     * @param status HTTP 状态码
     * @param value 响应数据
     * @return JSON 响应
     */
    private static Response createJsonResponse(int status, Object value) {
        return new Response(status, "application/json; charset=utf-8", JSON.toJSONBytes(value, JSONWriter.Feature.WriteNulls), Map.of());
    }
}
