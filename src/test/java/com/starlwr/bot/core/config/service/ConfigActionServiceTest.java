package com.starlwr.bot.core.config.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.StarBotConfiguration;
import com.starlwr.bot.core.config.ConfigException;
import com.starlwr.bot.core.config.action.ConfigActionHandler;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 配置中心动作服务测试
 */
class ConfigActionServiceTest {
    @TempDir
    Path directory;

    /**
     * 同步动作使用页面填写的值，未填写字段使用默认值，其他分组的错误不影响执行
     */
    @Test
    void executesSynchronousActionWithDraftAndPageDefaults() throws Exception {
        ConfigMetaRegistry registry = registry(true, EchoAction.class);
        try (ConfigActionService service = createActions(registry)) {
            JSONObject draft = JSONObject.of("starbot", JSONObject.of("actions", JSONObject.of("value", "未保存"),
                    "core", JSONObject.of("mail", JSONObject.of("enabled", true))));
            JSONObject result = service.execute(request(draft));
            assertThat(result.getString("state")).isEqualTo("succeeded");
            assertThat(result.getString("message")).isEqualTo("未保存/25");
            assertThat(draft).doesNotContainKey("advanced");
            assertThat(service.getStatus(result.getString("id"))).isEqualTo(result);
            assertThat(service.execute(request(new JSONObject())).getString("message")).isEqualTo("默认值/25");
        }
    }

    /**
     * 后台动作执行时可查询状态，重复请求复用原任务和原配置
     */
    @Test
    void protectsRunningActionAndKeepsSubmittedSnapshot() throws Exception {
        BlockingAction.started = new CountDownLatch(1);
        BlockingAction.release = new CountDownLatch(1);
        BlockingAction.calls.set(0);
        try (ConfigActionService service = createActions(registry(false, BlockingAction.class))) {
            JSONObject draft = JSONObject.of("starbot", JSONObject.of("actions", JSONObject.of("value", "第一次")));
            JSONObject first = service.execute(request(draft));
            assertThat(BlockingAction.started.await(5, TimeUnit.SECONDS)).isTrue();
            draft.getJSONObject("starbot").getJSONObject("actions").put("value", "第二次");
            JSONObject second = service.execute(request(draft));
            assertThat(second.getString("id")).isEqualTo(first.getString("id"));
            assertThat(service.getStatus(first.getString("id")).getString("state")).isEqualTo("running");
            BlockingAction.release.countDown();
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                JSONObject result = service.getStatus(first.getString("id"));
                assertThat(result.getString("state")).isEqualTo("succeeded");
                assertThat(result.getString("message")).isEqualTo("第一次");
            });
            assertThat(BlockingAction.calls).hasValue(1);
        } finally {
            BlockingAction.release.countDown();
        }
    }

    /**
     * 同步请求等待动作完成，等待期间仍可处理重复执行请求
     */
    @Test
    void waitsForSynchronousActionWithoutHoldingServiceLock() throws Exception {
        BlockingAction.started = new CountDownLatch(1);
        BlockingAction.release = new CountDownLatch(1);
        BlockingAction.calls.set(0);
        try (ConfigActionService service = createActions(registry(true, BlockingAction.class))) {
            CompletableFuture<JSONObject> first = CompletableFuture.supplyAsync(() -> service.execute(request(new JSONObject())));
            assertThat(BlockingAction.started.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<JSONObject> second = CompletableFuture.supplyAsync(() -> service.execute(request(new JSONObject())));
            assertThat(first).isNotDone();
            BlockingAction.release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getString("state")).isEqualTo("succeeded");
            assertThat(second.get(5, TimeUnit.SECONDS).getString("state")).isEqualTo("succeeded");
        } finally {
            BlockingAction.release.countDown();
        }
    }

    /**
     * 同步和后台动作均返回异常类名及消息，完整堆栈记录到控制台
     */
    @Test
    void reportsExceptionClassAndMessageForFailedActions() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigActionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (boolean synchronous : List.of(true, false)) {
                try (ConfigActionService service = createActions(registry(synchronous, FailingAction.class))) {
                    JSONObject result = service.execute(request(new JSONObject()));
                    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                        JSONObject status = service.getStatus(result.getString("id"));
                        assertThat(status.getString("state")).isEqualTo("failed");
                        assertThat(status.getString("message")).isEqualTo("执行出错, 详情请查看 StarBot 控制台: java.lang.IllegalStateException: 测试动作失败");
                    });
                    assertThat(appender.list).singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("配置动作执行失败");
                        assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).contains(
                                "java.lang.IllegalStateException: 测试动作失败", FailingAction.class.getName() + ".execute(");
                    });
                    appender.list.clear();
                }
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 处理器构造失败时返回原始异常类名，空消息不显示 null
     */
    @Test
    void reportsOriginalConstructorFailureWithoutNullMessage() throws Exception {
        try (ConfigActionService service = createActions(registry(true, ConstructorFailingAction.class))) {
            JSONObject result = service.execute(request(new JSONObject()));
            assertThat(result.getString("state")).isEqualTo("failed");
            assertThat(result.getString("message")).isEqualTo("执行出错, 详情请查看 StarBot 控制台: java.lang.IllegalArgumentException");
        }
    }

    /**
     * 拒绝参数无效或动作未声明的请求，查询失效任务时返回提示
     */
    @Test
    void rejectsUnknownActionsInvalidParametersAndExpiredTasks() throws Exception {
        try (ConfigActionService service = createActions(registry(true, EchoAction.class))) {
            JSONObject invalid = request(JSONObject.of("starbot", JSONObject.of("actions", JSONObject.of("value", true))));
            assertThatThrownBy(() -> service.execute(invalid)).isInstanceOf(ConfigException.class).hasMessageContaining("修正此操作需要的配置");
            JSONObject missing = request(new JSONObject());
            missing.put("action", "unknown");
            assertThatThrownBy(() -> service.execute(missing)).isInstanceOf(ConfigException.class).hasMessageContaining("不存在");
            assertThatThrownBy(() -> service.getStatus("unknown")).isInstanceOf(ConfigException.class).hasMessageContaining("失效");
        }
    }

    /**
     * 页面附带额外字段时，处理器仍只收到服务端元数据声明的参数
     */
    @Test
    void passesOnlyDeclaredParametersAndIgnoresClientOverrides() throws Exception {
        try (ConfigActionService service = createActions(registry(true, InspectAction.class))) {
            JSONObject draft = JSONObject.of("starbot", JSONObject.of("actions", JSONObject.of("value", "当前值"),
                    "core", JSONObject.of("config-center", JSONObject.of("password", "unrelated-secret"))),
                    "advanced", JSONObject.of("spring.mail.port", 2525, "spring.datasource.password", "unrelated-secret"));
            JSONObject request = request(draft);
            request.put("handler", "java.lang.Runtime");
            request.put("parameters", JSONObject.of("secret", JSONObject.of("path", JSONArray.of("starbot", "core", "config-center", "password"))));
            JSONObject result = service.execute(request);
            JSONObject received = JSON.parseObject(result.getString("message"));
            assertThat(received.keySet()).containsExactlyInAnyOrder("value", "port");
            assertThat(received.getString("value")).isEqualTo("当前值");
            assertThat(received.getIntValue("port")).isEqualTo(2525);
            assertThat(result.toJSONString()).doesNotContain("unrelated-secret");
        }
    }

    /**
     * 根据键名前缀筛选并展开高级配置，补充所选字段的默认值
     */
    @Test
    void selectsPrefixAndDefaultsWithoutIncludingUnrelatedProperties() throws Exception {
        JSONObject parameters = JSONObject.of("smtp", JSONObject.of("springProperties", true, "keyPrefix", "spring.mail."),
                "missing", JSONObject.of("path", JSONArray.of("starbot", "unknown")));
        try (ConfigActionService service = createActions(registry(true, InspectAction.class, parameters))) {
            JSONObject result = service.execute(request(JSONObject.of("advanced", JSONObject.of(
                    "spring.mail.properties", JSONObject.of("mail.smtp.localhost", "draft.example.test"),
                    "spring.mailbox.password", "unrelated-secret",
                    "spring.datasource.password", "unrelated-secret"))));
            JSONObject received = JSON.parseObject(result.getString("message"));
            assertThat(received.keySet()).containsExactlyInAnyOrder("smtp", "missing");
            assertThat(received.get("missing")).isNull();
            assertThat(received.getJSONObject("smtp").keySet()).allMatch(key -> key.startsWith("spring.mail."));
            assertThat(received.getJSONObject("smtp").getIntValue("spring.mail.port")).isEqualTo(25);
            assertThat(received.getJSONObject("smtp").getString("spring.mail.properties[mail.smtp.localhost]")).isEqualTo("draft.example.test");
            assertThat(result.toJSONString()).doesNotContain("unrelated-secret");
        }
    }

    /**
     * 根据完整键名筛选高级配置，分别读取用户填写的 Spring 属性和校验后的 Map
     */
    @Test
    void selectsExactSpringKeysBeforeExpandingProperties() throws Exception {
        JSONObject parameters = JSONObject.of("smtp", JSONObject.of("springProperties", true,
                "keys", JSONArray.of("spring.mail.port", "spring.mail.properties")),
                "properties", JSONObject.of("path", JSONArray.of("advanced", "spring.mail.properties")));
        JSONObject submittedProperties = JSONObject.of("mail.smtp.timeout", 5000);
        try (ConfigActionService service = createActions(registry(true, InspectAction.class, parameters))) {
            JSONObject result = service.execute(request(JSONObject.of("advanced", JSONObject.of(
                    "spring.mail.properties", submittedProperties,
                    "spring.mail.properties-extra", "unrelated",
                    "spring.mail.host", "smtp.example.test"))));
            JSONObject received = JSON.parseObject(result.getString("message"));
            JSONObject smtp = received.getJSONObject("smtp");
            assertThat(smtp).containsExactlyInAnyOrderEntriesOf(JSONObject.of(
                    "spring.mail.port", 25, "spring.mail.properties[mail.smtp.timeout]", 5000,
                    "spring.mail.properties[mail.smtp.auth]", false, "spring.mail.properties[mail.smtp.starttls.enable]", false,
                    "spring.mail.properties[mail.smtp.starttls.required]", false, "spring.mail.properties[mail.smtp.ssl.enable]", false));
            assertThat(received.getJSONObject("properties")).containsExactlyInAnyOrderEntriesOf(JSONObject.of(
                    "mail.smtp.timeout", "5000", "mail.smtp.auth", false, "mail.smtp.starttls.enable", false,
                    "mail.smtp.starttls.required", false, "mail.smtp.ssl.enable", false));
            assertThat(submittedProperties.get("mail.smtp.timeout")).isEqualTo(5000);
        }
    }

    /**
     * 两个参数选中同一对象时，各自持有独立的值
     * @param samePath 两个参数是否选择相同路径
     * @param firstPrefix 第一个参数的键名前缀
     * @param secondPrefix 第二个参数的键名前缀
     */
    @ParameterizedTest
    @CsvSource({"false,nested,", "true,nested,nes", "true,nes,nested", "true,nested,", "true,,nested", "true,,"})
    void keepsOverlappingArgumentsIndependent(boolean samePath, String firstPrefix, String secondPrefix) throws Exception {
        JSONArray path = JSONArray.of("starbot", "actions", "options");
        JSONObject first = JSONObject.of("path", path);
        JSONObject second = JSONObject.of("path", samePath ? path : JSONArray.of("starbot", "actions", "options", "nested"));
        if (firstPrefix != null) {
            first.put("keyPrefix", firstPrefix);
        }
        if (secondPrefix != null) {
            second.put("keyPrefix", secondPrefix);
        }
        JSONObject parameters = JSONObject.of("first", first, "second", second);
        JSONObject nested = JSONObject.of("value", "用户填写的值");
        JSONObject configuration = JSONObject.of("starbot", JSONObject.of("actions", JSONObject.of("options", JSONObject.of("nested", nested))));
        try (ConfigActionService service = createActions(registry(true, MutatingAction.class, parameters))) {
            JSONObject result = service.execute(request(configuration));
            assertThat(result.getString("state")).isEqualTo("succeeded");
            assertThat(result.getString("message")).isEqualTo("用户填写的值");
            assertThat(nested.getString("value")).isEqualTo("用户填写的值");
        }
    }

    /**
     * 合并父子路径时保留显式空值，原请求中的嵌套对象保持不变
     */
    @Test
    void mergesSelectedPathsWithoutChangingSubmittedObjects() throws Exception {
        JSONObject parameters = JSONObject.of(
                "options", JSONObject.of("path", JSONArray.of("starbot", "actions", "options")),
                "nestedValue", JSONObject.of("path", JSONArray.of("starbot", "actions", "options", "nested", "value")));
        Map<String, String> nested = Map.of("value", "用户填写的值");
        JSONObject options = JSONObject.of("nested", nested, "empty", null);
        JSONObject configuration = JSONObject.of("starbot", JSONObject.of("actions", JSONObject.of("options", options)));
        try (ConfigActionService service = createActions(registry(true, InspectAction.class, parameters))) {
            JSONObject result = service.execute(request(configuration));
            assertThat(result.getString("state")).isEqualTo("succeeded");
            JSONObject received = JSON.parseObject(result.getString("message"));
            assertThat(received.getString("nestedValue")).isEqualTo("用户填写的值");
            assertThat(received.getJSONObject("options")).containsEntry("empty", null);
            assertThat(options.get("nested")).isSameAs(nested);
        }
    }

    /**
     * 展开 Spring 属性时，空对象与原请求及其他参数保持独立
     */
    @Test
    void keepsEmptySpringPropertyObjectsIndependent() throws Exception {
        JSONObject parameters = JSONObject.of(
                "first", JSONObject.of("springProperties", true, "keys", JSONArray.of("nested")),
                "second", JSONObject.of("path", JSONArray.of("advanced", "nested")));
        JSONObject nested = new JSONObject();
        try (ConfigActionService service = createActions(registry(true, MutatingAction.class, parameters))) {
            JSONObject result = service.execute(request(JSONObject.of("advanced", JSONObject.of("nested", nested))));
            assertThat(result.getString("state")).isEqualTo("succeeded");
            assertThat(result.getString("message")).isEqualTo("执行成功");
            assertThat(nested).isEmpty();
        }
    }

    /**
     * 用户填写的完整键名覆盖 Map 建议值，显式 null 取消对应的建议值
     */
    @Test
    void mergesExplicitSpringPropertiesWithMapSuggestions() throws Exception {
        JSONObject parameters = JSONObject.of("smtp", JSONObject.of("springProperties", true, "keyPrefix", "example.mail."));
        JSONObject mailProperties = JSONObject.of("key", "example.mail.properties", "type", "map",
                "values", JSONObject.of("type", "string"),
                "suggestedValue", JSONObject.of("mail.smtp.connectiontimeout", "10000", "mail.smtp.timeout", "8000", "mail.smtp.writetimeout", "6000"));
        try (ConfigActionService service = createActions(registry(true, InspectAction.class, parameters, mailProperties))) {
            JSONObject result = service.execute(request(JSONObject.of("advanced", JSONObject.of(
                    "example.mail.properties.mail.smtp.connectiontimeout", "5000",
                    "example.mail.properties.mail.smtp.writetimeout", null))));
            JSONObject smtp = JSON.parseObject(result.getString("message")).getJSONObject("smtp");
            assertThat(smtp).containsExactlyInAnyOrderEntriesOf(JSONObject.of(
                    "example.mail.properties.mail.smtp.connectiontimeout", "5000",
                    "example.mail.properties[mail.smtp.timeout]", "8000"));
        }
    }

    /**
     * 用户以两种写法填写同一 Spring 属性时拒绝执行，并报告两处位置
     */
    @Test
    void rejectsDuplicateExplicitSpringPropertiesBeforeExecuting() throws Exception {
        JSONObject parameters = JSONObject.of("smtp", JSONObject.of("springProperties", true, "keyPrefix", "spring.mail."));
        try (ConfigActionService service = createActions(registry(true, InspectAction.class, parameters))) {
            JSONObject draft = JSONObject.of("advanced", JSONObject.of(
                    "spring.mail.properties", JSONObject.of("mail.smtp.timeout", "8000"),
                    "spring.mail.properties.mail.smtp.timeout", "5000"));

            assertThatThrownBy(() -> service.execute(request(draft))).isInstanceOfSatisfying(ConfigException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(422);
                JSONArray errors = ((JSONObject) error.getDetails()).getJSONArray("errors");
                assertThat(errors).hasSize(1);
                JSONObject duplicate = errors.getJSONObject(0);
                assertThat(duplicate.getString("code")).isEqualTo("duplicateSpringProperty");
                assertThat(duplicate.getJSONArray("path")).containsExactly("advanced", "spring.mail.properties", "mail.smtp.timeout");
                assertThat(duplicate.getJSONArray("relatedPath")).containsExactly("advanced", "spring.mail.properties.mail.smtp.timeout");
            });
        }
    }

    /**
     * 动作没声明参数时只收到空对象
     */
    @Test
    void passesEmptyObjectWhenNoParametersAreDeclared() throws Exception {
        try (ConfigActionService service = createActions(registry(true, InspectAction.class, new JSONObject()))) {
            JSONObject result = service.execute(request(JSONObject.of("advanced", JSONObject.of("password", "unrelated-secret"))));
            assertThat(JSON.parseObject(result.getString("message"))).isEmpty();
        }
    }

    /**
     * 关闭服务会结束后台状态，且不再接受新动作
     */
    @Test
    void stopsBackgroundActionsWhenClosed() throws Exception {
        BlockingAction.started = new CountDownLatch(1);
        BlockingAction.release = new CountDownLatch(1);
        try (ConfigActionService service = createActions(registry(false, BlockingAction.class))) {
            service.execute(request(new JSONObject()));
            assertThat(BlockingAction.started.await(5, TimeUnit.SECONDS)).isTrue();
            service.close();
            assertThatThrownBy(() -> service.execute(request(new JSONObject()))).isInstanceOf(ConfigException.class).hasMessageContaining("关闭");
        } finally {
            BlockingAction.release.countDown();
        }
    }

    /**
     * 动作所需配置的类型不一致时，拒绝未选择配置值的请求，并将已选择的配置值传给处理器
     */
    @Test
    void executesWithExplicitTypeConflictParametersAndRejectsMissingChoices() throws Exception {
        registry(true, EchoAction.class);
        Path plugins = directory.resolve("plugins");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("port-type.jar")))) {
            for (String resource : List.of("plugin.json", "config/config.json")) {
                jar.putNextEntry(new JarEntry(resource));
                String content = "plugin.json".equals(resource) ? "{\"groupId\":\"test\",\"artifactId\":\"port-type\",\"name\":\"Port type\"}" : """
                        {"title":"Port type","basePath":["starbot","port-type"],"groups":[{"id":"main","title":"设置","target":"advanced",
                         "fields":[{"key":"spring.mail.port","type":"string","suggestedValue":"custom","validators":[{"rule":"maxLength","value":1}]}]}]}
                        """;
                jar.write(content.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, true);
        try (ConfigActionService actions = new ConfigActionService(registry, springPropertyBridge)) {
            assertThatThrownBy(() -> actions.execute(request(new JSONObject()))).isInstanceOf(ConfigException.class).hasMessageContaining("冲突");
            JSONObject result = actions.execute(request(JSONObject.of("starbot", JSONObject.of("actions", JSONObject.of("value", "chosen")),
                    "advanced", JSONObject.of("spring.mail.port", 55))));
            assertThat(result.getString("state")).isEqualTo("succeeded");
            assertThat(result.getString("message")).isEqualTo("chosen/55");
        } finally {
            config.destroy();
        }
    }

    /**
     * 创建带默认动作参数的测试插件并加载它的元数据
     * @param synchronous 是否同步等待结果
     * @param handler 动作处理器类型
     * @return 配置注册表
     */
    private ConfigMetaRegistry registry(boolean synchronous, Class<? extends ConfigActionHandler> handler) throws Exception {
        return registry(synchronous, handler, JSONObject.of("value", JSONObject.of("path", JSONArray.of("starbot", "actions", "value")),
                "port", JSONObject.of("path", JSONArray.of("advanced", "spring.mail.port"))));
    }

    /**
     * 将指定的动作参数写入测试插件的元数据并加载
     * @param synchronous 是否同步等待结果
     * @param handler 动作处理器类型
     * @param parameters 写入动作元数据的 parameters
     * @param advancedFields 额外声明的高级配置字段
     * @return 配置注册表
     */
    private ConfigMetaRegistry registry(boolean synchronous, Class<? extends ConfigActionHandler> handler, JSONObject parameters,
                                        JSONObject... advancedFields) throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        JSONObject action = JSONObject.of("id", "echo", "label", "执行", "handler", handler.getName(), "synchronous", synchronous, "parameters", parameters);
        JSONObject group = JSONObject.of("id", "settings", "title", "设置", "path", new JSONArray(),
                "fields", JSONArray.of(JSONObject.of("key", "value", "label", "值", "type", "string")),
                "actions", JSONArray.of(action));
        JSONArray groups = JSONArray.of(group);
        if (advancedFields.length > 0) {
            groups.add(JSONObject.of("id", "advanced", "title", "高级配置", "target", "advanced", "fields", new JSONArray(List.of(advancedFields))));
        }
        JSONObject moduleMeta = JSONObject.of("title", "Actions", "basePath", JSONArray.of("starbot", "actions"), "groups", groups);
        moduleMeta.put("configurationClass", ActionProperties.class.getName());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("actions.jar")))) {
            for (String name : List.of("plugin.json", "config/config.json")) {
                jar.putNextEntry(new JarEntry(name));
                String text = name.equals("plugin.json") ? "{\"groupId\":\"test\",\"artifactId\":\"actions\",\"name\":\"Actions\"}" : moduleMeta.toJSONString();
                jar.write(text.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, true);
        config.destroy();
        return registry;
    }

    /**
     * 动作测试配置类
     */
    @Getter
    @StarBotConfiguration(prefix = "starbot.actions")
    public static class ActionProperties {
        private final String value = "默认值";
    }

    /**
     * 构造当前测试插件的动作请求
     * @param configuration 页面配置
     * @return 动作请求
     */
    private static JSONObject request(JSONObject configuration) {
        return JSONObject.of("module", "test:actions", "group", "settings", "action", "echo", "configuration", configuration);
    }

    /**
     * 创建配置中心动作服务
     * @param registry 配置元数据注册表
     * @return 配置中心动作服务
     */
    private static ConfigActionService createActions(ConfigMetaRegistry registry) {
        return new ConfigActionService(registry, new SpringPropertyBridge(registry));
    }

    /**
     * 配置回显测试处理器
     */
    public static class EchoAction implements ConfigActionHandler {
        /**
         * 回显收到的业务值和邮件端口
         * @param configuration 动作参数
         * @return 业务值与邮件端口组成的文本
         */
        @Override
        public String execute(JSONObject configuration) {
            return configuration.getString("value") + "/" + configuration.getIntValue("port");
        }
    }

    /**
     * 阻塞测试处理器
     */
    public static class BlockingAction implements ConfigActionHandler {
        private static CountDownLatch started;
        private static CountDownLatch release;
        private static final AtomicInteger calls = new AtomicInteger();

        /**
         * 等待测试释放信号后返回提交时的值
         * @param configuration 页面配置
         * @return 提交值
         */
        @Override
        public String execute(JSONObject configuration) throws InterruptedException {
            calls.incrementAndGet();
            started.countDown();
            release.await();
            return configuration.getString("value");
        }
    }

    /**
     * 执行失败测试处理器
     */
    public static class FailingAction implements ConfigActionHandler {
        /**
         * 模拟处理器执行失败
         * @param configuration 页面配置
         * @return 不返回正常结果
         */
        @Override
        public String execute(JSONObject configuration) {
            throw new IllegalStateException("测试动作失败");
        }
    }

    /**
     * 构造失败测试处理器
     */
    public static class ConstructorFailingAction extends FailingAction {
        public ConstructorFailingAction() {
            throw new IllegalArgumentException();
        }
    }

    /**
     * 参数回显测试处理器
     */
    public static class InspectAction implements ConfigActionHandler {
        /**
         * 序列化参数供测试检查选择范围
         * @param parameters 动作参数
         * @return 参数 JSON
         */
        @Override
        public String execute(JSONObject parameters) {
            return JSON.toJSONString(parameters, com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls);
        }
    }

    /**
     * 参数隔离测试处理器
     */
    public static class MutatingAction implements ConfigActionHandler {
        /**
         * 修改第一个参数，读取第二个参数中的值
         * @param parameters 动作参数
         * @return 第二个参数中的值
         */
        @Override
        public String execute(JSONObject parameters) {
            parameters.getJSONObject("first").getJSONObject("nested").put("value", "处理器改写的值");
            JSONObject second = parameters.getJSONObject("second");
            return (second.containsKey("nested") ? second.getJSONObject("nested") : second).getString("value");
        }
    }
}
