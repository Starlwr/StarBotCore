package com.starlwr.bot.core.config.action;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.service.ConfigActionService;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 测试邮件发送动作处理器测试
 */
class SendTestMailActionTest {
    @TempDir
    Path directory;

    /**
     * 发送测试邮件时使用页面填写的值，不保存或应用这些配置
     */
    @Test
    void sendsDraftMailWithoutSavingOrChangingActiveConfiguration() throws Exception {
        Path file = directory.resolve("config.json");
        String original = "{\"advanced\":{\"spring.mail.host\":\"saved.invalid\"}}";
        Files.writeString(file, original);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        ConfigService config = new ConfigService(file, registry, springPropertyBridge, true);
        try (SmtpServer smtp = new SmtpServer(); ConfigActionService actions = new ConfigActionService(registry, springPropertyBridge)) {
            JSONObject draft = draft(smtp.server.getLocalPort());
            draft.getJSONObject("advanced").put("spring.mail.properties", JSONObject.of("mail.smtp.localhost", "structured-client.example.test"));
            JSONObject result = actions.execute(JSONObject.of("module", "core", "group", "mail", "action", "test-mail-send", "configuration", draft));
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(actions.getStatus(result.getString("id")).getString("state")).isEqualTo("succeeded"));
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(smtp.message.get(5, TimeUnit.SECONDS).getBytes(StandardCharsets.UTF_8)));
            assertThat(message.getSubject()).isEqualTo("StarBot 测试邮件");
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("receiver@example.test");
            assertThat(message.getFrom()[0].toString()).isEqualTo("sender@example.test");
            assertThat(message.getContent().toString()).contains("当前页面填写", "不会保存配置");
            assertThat(smtp.commands).contains("EHLO structured-client.example.test");
            assertThat(Files.readString(file)).isEqualTo(original);
            assertThat(config.get("advanced", JSONObject.class).getString("spring.mail.host")).isEqualTo("saved.invalid");
            assertThat(config.get("starbot.core.mail.enabled", Boolean.class)).isFalse();
            assertThat(draft.getJSONObject("advanced")).doesNotContainKey("spring.mail.protocol");
        } finally {
            config.destroy();
        }
    }

    /**
     * 根据页面配置为临时邮件发送器设置认证参数和包含点号的自定义属性
     */
    @Test
    void appliesDraftAuthenticationAndAdditionalProperties() throws Exception {
        try (SmtpServer smtp = new SmtpServer()) {
            JSONObject draft = draft(smtp.server.getLocalPort());
            JSONObject advanced = draft.getJSONObject("advanced");
            advanced.put("spring.mail.properties.mail.smtp.auth", true);
            advanced.put("spring.mail.properties.mail.smtp.auth.mechanisms", "LOGIN");
            advanced.put("spring.mail.properties.mail.smtp.localhost", "draft-client.example.test");
            String result = new SendTestMailAction().execute(JSONObject.of("mail", draft.getJSONObject("starbot").getJSONObject("core").getJSONObject("mail"), "smtp", advanced));
            assertThat(result).contains("已发送");
            assertThat(smtp.commands).contains("EHLO draft-client.example.test", "AUTH LOGIN", "user=sender@example.test", "password=draft-password");
        }
    }

    /**
     * 未开启邮件或未提供收件人时不尝试建立 SMTP 连接
     */
    @Test
    void requiresEnabledMailAndRecipient() {
        JSONObject draft = draft(1);
        draft.getJSONObject("starbot").getJSONObject("core").getJSONObject("mail").put("enabled", false);
        JSONObject parameters = JSONObject.of("mail", draft.getJSONObject("starbot").getJSONObject("core").getJSONObject("mail"), "smtp", draft.getJSONObject("advanced"));
        assertThatThrownBy(() -> new SendTestMailAction().execute(parameters)).hasMessageContaining("开启邮件");
        draft.getJSONObject("starbot").getJSONObject("core").getJSONObject("mail").put("enabled", true);
        draft.getJSONObject("starbot").getJSONObject("core").getJSONObject("mail").put("default-to", "");
        assertThatThrownBy(() -> new SendTestMailAction().execute(parameters)).hasMessageContaining("收件邮箱");
    }

    /**
     * 创建只指向本机的邮件草稿
     * @param port SMTP 监听端口
     * @return 页面配置
     */
    private static JSONObject draft(int port) {
        return JSONObject.of("starbot", JSONObject.of("core", JSONObject.of("mail", JSONObject.of("enabled", true, "default-to", "receiver@example.test"))),
                "advanced", JSONObject.of("spring.mail.host", "127.0.0.1", "spring.mail.port", port,
                        "spring.mail.username", "sender@example.test", "spring.mail.password", "draft-password"));
    }

    /**
     * SMTP 测试服务器
     */
    private static class SmtpServer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));

        /**
         * 收到的 SMTP 命令列表
         */
        private final List<String> commands = new ArrayList<>();
        private final CompletableFuture<String> message = new CompletableFuture<>();
        private final CompletableFuture<Void> worker;
        private volatile Socket client;

        private SmtpServer() throws Exception {
            worker = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept();
                     BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    client = socket;
                    socket.setSoTimeout(5000);
                    reply(output, "220 local-test ESMTP");
                    String line;
                    while ((line = input.readLine()) != null) {
                        commands.add(line);
                        if (line.startsWith("EHLO")) {
                            reply(output, "250-local-test\r\n250-AUTH LOGIN\r\n250 OK");
                        } else if (line.equals("AUTH LOGIN")) {
                            reply(output, "334 VXNlcm5hbWU6");
                            commands.add("user=" + new String(Base64.getDecoder().decode(input.readLine()), StandardCharsets.UTF_8));
                            reply(output, "334 UGFzc3dvcmQ6");
                            commands.add("password=" + new String(Base64.getDecoder().decode(input.readLine()), StandardCharsets.UTF_8));
                            reply(output, "235 Authentication successful");
                        } else if (line.equals("DATA")) {
                            reply(output, "354 End with a dot");
                            StringBuilder content = new StringBuilder();
                            while ((line = input.readLine()) != null && !line.equals(".")) {
                                content.append(line).append("\r\n");
                            }
                            message.complete(content.toString());
                            reply(output, "250 Accepted");
                        } else if (line.equals("QUIT")) {
                            reply(output, "221 Bye");
                            break;
                        } else {
                            reply(output, "250 OK");
                        }
                    }
                } catch (Exception e) {
                    message.completeExceptionally(e);
                }
            });
        }

        /**
         * 写入一条 SMTP 响应
         * @param output 连接输出
         * @param text 响应文本
         */
        private static void reply(BufferedWriter output, String text) throws Exception {
            output.write(text + "\r\n");
            output.flush();
        }

        /**
         * 释放监听端口及客户端连接
         */
        @Override
        public void close() throws Exception {
            server.close();
            if (client != null) {
                client.close();
            }
            worker.get(5, TimeUnit.SECONDS);
        }
    }
}
