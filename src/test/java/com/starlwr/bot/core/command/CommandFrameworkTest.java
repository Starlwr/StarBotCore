package com.starlwr.bot.core.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.command.builtin.DisableCommand;
import com.starlwr.bot.core.command.builtin.EnableCommand;
import com.starlwr.bot.core.command.builtin.PingCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.StarBotCoreThreadPoolConfig;
import com.starlwr.bot.core.message.GroupRole;
import com.starlwr.bot.core.message.Message;
import com.starlwr.bot.core.message.PushTargetType;
import com.starlwr.bot.core.message.StarBotMessageSender;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命令框架测试
 * <p>
 * 覆盖命令注册与匹配、权限和作用域、禁用策略持久化、并发调度、关闭行为、回复及 Spring 命令发现等核心逻辑和边界。
 */
class CommandFrameworkTest {
    /**
     * 每个测试独立使用的命令策略目录
     */
    @TempDir
    private Path directory;

    private StarBotCoreProperties properties;

    private StarBotCommandPolicy policy;

    private ApplicationContext applicationContext;

    private StarBotCommandRegistry registry;

    private ThreadPoolTaskExecutor executor;

    private StarBotCommandService service;

    private StarBotMessageSender sender;

    private List<Message> replies;

    /**
     * 创建彼此隔离的命令注册表、策略和线程池
     */
    @BeforeEach
    void setup() {
        properties = new StarBotCoreProperties();
        properties.getCommand().setDisabledCommandPath(directory.resolve("disabled-command.json").toString());
        policy = new StarBotCommandPolicy(properties);
        policy.onApplicationReadyEvent();
        applicationContext = mock(ApplicationContext.class);
        registry = new StarBotCommandRegistry(applicationContext);
        executor = new StarBotCoreThreadPoolConfig(properties).commandThreadPool();
        service = new StarBotCommandService(executor, properties, registry, policy);
        sender = mock(StarBotMessageSender.class);
        replies = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            replies.add(call.getArgument(0));
            return null;
        }).when(sender).send(any());
    }

    /**
     * 关闭测试创建的命令服务和线程池
     */
    @AfterEach
    void cleanup() {
        service.close();
        executor.shutdown();
    }

    /**
     * 创建标准群聊命令消息
     *
     * @param content 消息内容
     * @return 命令消息
     */
    private CommandMessage message(String content) {
        return messageBuilder(content).build();
    }

    /**
     * 创建标准群聊命令消息构建器
     *
     * @param content 消息内容
     * @return 命令消息构建器
     */
    private CommandMessage.CommandMessageBuilder messageBuilder(String content) {
        return CommandMessage.builder().platform("bot-a").botId(1L)
                .senderId(2L).groupId(100L).type(PushTargetType.GROUP).content(content)
                .rawMessage(new JSONObject().fluentPut("raw_message", content)).messageSender(sender);
    }

    /**
     * 根据命令消息创建已完成预处理的命令上下文
     *
     * @param message 命令消息
     * @return 命令上下文
     */
    private CommandContext context(CommandMessage message) {
        return CommandContext.tryCreate(message, "").orElseThrow();
    }

    /**
     * 创建名称和别名固定的测试命令
     *
     * @param id 命令 ID
     * @param run 执行逻辑
     * @return 测试命令
     */
    static StarBotCommand command(String id, Consumer<CommandContext> run) {
        return new StarBotCommand() {
            public String getId() {
                return id;
            }

            public String getName() {
                return "你好";
            }

            public List<String> getAliases() {
                return List.of("hello");
            }

            public String getDescription() {
                return "测试";
            }

            public void execute(CommandContext context) {
                run.accept(context);
            }
        };
    }

    /**
     * 提交命令并等待执行完成
     *
     * @param message 命令消息
     */
    private void dispatch(CommandMessage message) throws Exception {
        submit(service, message).get(5, TimeUnit.SECONDS);
    }

    /**
     * 向指定命令服务提交上下文
     *
     * @param commandService 命令服务
     * @param message 命令消息
     * @return 异步完成信号
     */
    private CompletableFuture<Void> submit(StarBotCommandService commandService, CommandMessage message) {
        return commandService.dispatch(() -> Optional.of(message));
    }

    /**
     * 模拟 Spring 发现命令并刷新注册表
     *
     * @param discovered 已发现的命令
     */
    private void register(Collection<StarBotCommand> discovered) {
        Map<String, StarBotCommand> beans = new LinkedHashMap<>();
        int index = 0;
        for (StarBotCommand command : discovered) {
            beans.put("command-" + index++, command);
        }
        when(applicationContext.getBeansOfType(StarBotCommand.class)).thenReturn(beans);
        registry.onContextRefreshedEvent();
    }

    /**
     * 验证注册顺序、多命令匹配和重复 ID 保留首个命令
     */
    @Test
    void registrationOrderDuplicateIdAndMultiMatch() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        List<CommandContext> contexts = new CopyOnWriteArrayList<>();
        List<StarBotCommand> list = List.of(command("a", c -> {
                    order.add("a");
                    contexts.add(c);
                }), command("a", c -> fail("重复 ID 执行")), command("b", c -> {
                    order.add("b");
                    contexts.add(c);
                }));
        register(list);
        register(list);
        assertEquals(2, registry.getCommands().size());
        dispatch(message("HeLLo arg"));
        assertEquals(List.of("a", "b"), order);
        assertSame(contexts.get(0), contexts.get(1));
        assertEquals(2, registry.find("HELLO").size());
        assertEquals("a", registry.find("a").get(0).getId());
    }

    /**
     * 验证连通性命令通过统一调度匹配触发词并仅回复版本信息
     */
    @Test
    void pingCommandRepliesWithOnlyCoreVersion() throws Exception {
        Properties build = new Properties();
        build.setProperty("version", "3.0.0");
        register(List.of(new PingCommand(new BuildProperties(build))));

        dispatch(message("starbot"));
        properties.getCommand().setPrefix("/");
        dispatch(message("/StarBot"));
        dispatch(message("/status"));
        dispatch(message("/ping"));

        assertEquals(List.of("Running on StarBot v3.0.0", "Running on StarBot v3.0.0"),
                replies.stream().map(Message::getContent).toList());
    }

    /**
     * 验证代理命令的默认 ID 来自实际实现类
     */
    @Test
    void proxyDefaultIdUsesImplementationClass() {
        DefaultIdCommand target = new DefaultIdCommand();
        StarBotCommand proxy = (StarBotCommand) new ProxyFactory(target).getProxy();
        assertEquals(DefaultIdCommand.class.getName(), proxy.getId());
    }

    /**
     * 使用接口默认 ID 的测试命令
     */
    public static class DefaultIdCommand implements StarBotCommand {
        public String getName() {
            return "default";
        }

        public String getDescription() {
            return "default";
        }

        public void execute(CommandContext context) {
        }
    }

    /**
     * 验证全局前缀、提及要求以及私聊和回复豁免
     */
    @Test
    void globalGateAndPrivateMentionExemption() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        List<CommandContext> contexts = new CopyOnWriteArrayList<>();
        register(List.of(command("a", c -> {
            seen.add(c.getArgumentText());
            contexts.add(c);
        })));
        properties.getCommand().setPrefix("/");
        properties.getCommand().setMention(true);
        dispatch(message("/你好"));
        CommandMessage mentioned = messageBuilder(" {at=1} /HELLO {at=2} one {at=all} ").mentioned(true).build();
        dispatch(mentioned);
        dispatch(messageBuilder("/你好 two").replyToBot(true).build());
        dispatch(messageBuilder("/你好 three").type(PushTargetType.FRIEND).build());
        dispatch(messageBuilder("你好 bad").mentioned(true).build());
        assertEquals(List.of("one", "two", "three"), seen);
        assertEquals("/", contexts.get(0).getPrefix());
        assertEquals("{at=1} HELLO {at=2} one {at=all}", contexts.get(0).getContent());
    }

    /**
     * 验证命令参数会忽略消息元素并按空白分隔
     */
    @Test
    void commandArgumentsIgnoreElementsAndSplitOnWhitespace() {
        CommandMessage message = message(" {at=1} 你好 {at=2} 参数一  \t参数二　\"参数 三\""
                + "{image_path=/opt/a.jpg}'参数 四' {at=all} ");
        CommandContext context = context(message);
        assertEquals("你好", context.getCommandName());
        assertEquals("参数一  \t参数二　\"参数 三\" '参数 四'", context.getArgumentText());
        List<String> arguments = context.getArguments();
        assertEquals(List.of("参数一", "参数二", "\"参数", "三\"", "'参数", "四'"), arguments);

        CommandContext empty = context(message("你好"));
        assertEquals("", empty.getArgumentText());
        assertTrue(empty.getArguments().isEmpty());
    }

    /**
     * 验证命令上下文提供群名称和发送者群昵称
     */
    @Test
    void contextExposesGroupNames() {
        Instant timestamp = Instant.ofEpochSecond(1790667518L);
        CommandContext context = context(messageBuilder("你好")
                .groupName("测试群").groupNickName("群里的小明").timestamp(timestamp).build());
        assertEquals("测试群", context.getGroupName());
        assertEquals("群里的小明", context.getGroupNickName());
        assertEquals(timestamp, context.getTimestamp());

        CommandContext empty = context(message("你好"));
        assertEquals("", empty.getGroupName());
        assertEquals("", empty.getGroupNickName());
        assertNull(empty.getTimestamp());
    }

    /**
     * 验证前缀不匹配或没有命令文字时不创建上下文
     */
    @Test
    void contextCreationRejectsInvalidCommandText() {
        for (String content : new String[]{null, "", " \t　", "/", " / \t ",
                "{at=1} {image_url=xxx}", "{at=1} / {image_url=xxx}", "你好", "x/你好"}) {
            assertTrue(CommandContext.tryCreate(message(content), "/").isEmpty(), content);
        }
        for (String content : new String[]{null, "", " \t　", "{at=1} {image_url=xxx}"}) {
            assertTrue(CommandContext.tryCreate(message(content), "").isEmpty(), content);
        }
    }

    /**
     * 验证前缀按字面连续匹配，并复用解析后的命令名称和参数文本
     */
    @Test
    void contextCreationMatchesLiteralPrefixAndPreservesElements() {
        for (String prefix : new String[]{"", "/", "//", ".*", "[+]"}) {
            CommandContext context = CommandContext.tryCreate(message(" {at=1} " + prefix
                    + "{image_url=xxx} 查询\t张三　参数二 {at=2} "), prefix).orElseThrow();
            assertEquals(prefix, context.getPrefix());
            assertEquals("{at=1} {image_url=xxx} 查询\t张三　参数二 {at=2}", context.getContent());
            assertEquals("查询", context.getCommandName());
            assertEquals("张三　参数二", context.getArgumentText());
            assertEquals(List.of("张三", "参数二"), context.getArguments());
            assertEquals(List.of("{image_url=xxx}"), context.getImageArguments());
        }
        assertTrue(CommandContext.tryCreate(message("/你好"), "//").isEmpty());
        assertTrue(CommandContext.tryCreate(message("/{at=1}/你好"), "//").isEmpty());
        assertTrue(CommandContext.tryCreate(message("任意你好"), ".*").isEmpty());
    }

    /**
     * 验证图片元素不影响前缀和命令匹配，并通过独立参数列表保留
     */
    @Test
    void commandMatchingIgnoresAndPreservesImageArguments() throws Exception {
        List<CommandContext> contexts = new CopyOnWriteArrayList<>();
        register(List.of(command("a", contexts::add)));
        properties.getCommand().setPrefix("/");
        dispatch(messageBuilder(" {image_url=https://example.com/a.jpg} {at=1} "
                + "/{image_path=/opt/image.jpg} HELLO {image_base64=YWJj} text "
                + "{image_url=https://example.com/b.jpg} ").mentioned(true).build());
        assertEquals(1, contexts.size());
        CommandContext context = contexts.get(0);
        assertEquals("{image_url=https://example.com/a.jpg} {at=1} "
                + "{image_path=/opt/image.jpg} HELLO {image_base64=YWJj} text "
                + "{image_url=https://example.com/b.jpg}", context.getContent());
        assertEquals("HELLO", context.getCommandName());
        assertEquals("text", context.getArgumentText());
        assertEquals(List.of("text"), context.getArguments());
        List<String> images = context.getImageArguments();
        assertEquals(List.of("{image_url=https://example.com/a.jpg}", "{image_path=/opt/image.jpg}",
                "{image_base64=YWJj}", "{image_url=https://example.com/b.jpg}"), images);

        dispatch(message("/HE{image_url=https://example.com/a.jpg}LLO"));
        assertEquals(1, contexts.size());
    }

    /**
     * 验证自定义匹配规则，且单个命令匹配或执行异常不影响后续命令
     */
    @Test
    void customMatchingAndFailuresDoNotStopLaterCommands() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        StarBotCommand brokenMatcher = new DefaultIdCommand() {
            @Override
            public boolean isTrigger(CommandContext c) {
                throw new IllegalStateException("匹配失败");
            }
        };
        StarBotCommand custom = new DefaultIdCommand() {
            @Override
            public boolean isTrigger(CommandContext c) {
                return c.getContent().endsWith("你好");
            }

            @Override
            public void execute(CommandContext c) {
                seen.add("custom");
            }
        };
        register(List.of(brokenMatcher, command("bad", c -> {
            throw new IllegalStateException("执行失败");
        }),
                command("good", c -> seen.add("good")), custom));
        dispatch(message("你好"));
        assertEquals(List.of("good", "custom"), seen);
    }

    /**
     * 验证权限与会话作用域的完整组合
     */
    @Test
    void permissionAndScopeMatrix() {
        properties.getCommand().getOwners().put("bot-a", List.of(9L));
        for (CommandPermission permission : CommandPermission.values()) {
            for (CommandScope scope : CommandScope.values()) {
                StarBotCommand command = new DefaultIdCommand() {
                    @Override
                    public CommandPermission getPermission() {
                        return permission;
                    }

                    @Override
                    public CommandScope getScope() {
                        return scope;
                    }
                };
                for (PushTargetType type : List.of(PushTargetType.FRIEND, PushTargetType.GROUP)) {
                    for (var role : GroupRole.values()) {
                        for (long user : List.of(2L, 9L)) {
                            boolean expectedScope = scope == CommandScope.ALL || (scope == CommandScope.GROUP) == (type == PushTargetType.GROUP);
                            boolean isAdmin = type == PushTargetType.GROUP && (role == GroupRole.OWNER || role == GroupRole.ADMIN);
                            boolean expectedPermission = permission == CommandPermission.EVERYONE || user == 9L
                                    || permission == CommandPermission.ADMIN && isAdmin;
                            var decision = policy.evaluate(command, context(messageBuilder("x")
                                    .type(type).role(role).senderId(user).build()));
                            assertEquals(expectedScope && expectedPermission, decision.isAllowed(), permission + "/" + scope + "/" + role + "/" + type);
                        }
                    }
                }
            }
        }
    }

    /**
     * 验证配置权限可收紧或放宽命令默认权限，群聊角色和各平台主人规则仍然生效
     */
    @Test
    void permissionOverridesReplaceCommandDefaults() {
        properties.getCommand().getOwners().put("bot-a", List.of(9L));
        properties.getCommand().getOwners().put("bot-b", List.of(8L));
        for (CommandPermission defaultPermission : CommandPermission.values()) {
            StarBotCommand command = new DefaultIdCommand() {
                @Override
                public CommandPermission getPermission() {
                    return defaultPermission;
                }
            };
            for (CommandPermission override : CommandPermission.values()) {
                properties.getCommand().getPermissions().put(command.getId(), override);
                for (String platform : List.of("bot-a", "bot-b", "unconfigured")) {
                    for (PushTargetType type : List.of(PushTargetType.FRIEND, PushTargetType.GROUP)) {
                        for (var role : GroupRole.values()) {
                            for (long user : List.of(2L, 8L, 9L)) {
                                boolean owner = "bot-a".equals(platform) && user == 9L || "bot-b".equals(platform) && user == 8L;
                                boolean admin = type == PushTargetType.GROUP && (role == GroupRole.OWNER || role == GroupRole.ADMIN);
                                boolean expected = override == CommandPermission.EVERYONE || owner || override == CommandPermission.ADMIN && admin;
                                var decision = policy.evaluate(command, context(messageBuilder("x")
                                        .platform(platform).type(type).role(role).senderId(user).build()));
                                assertEquals(expected, decision.isAllowed(), defaultPermission + "/" + override + "/" + platform + "/" + type + "/" + role + "/" + user);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 验证权限覆盖按命令 ID 匹配，并应用于命令名称和别名的实际执行
     */
    @Test
    void permissionOverridesApplyToDispatchById() throws Exception {
        List<String> executed = new CopyOnWriteArrayList<>();
        var restricted = command("test.restricted", c -> executed.add("restricted"));
        var unchanged = command("test.unchanged", c -> executed.add("unchanged"));
        register(List.of(restricted, unchanged));
        properties.getCommand().getPermissions().put("你好", CommandPermission.OWNER);
        properties.getCommand().getPermissions().put("hello", CommandPermission.OWNER);
        dispatch(message("hello"));
        assertEquals(List.of("restricted", "unchanged"), executed);
        assertTrue(replies.isEmpty());

        executed.clear();
        properties.getCommand().getPermissions().put(restricted.getId(), CommandPermission.OWNER);
        dispatch(message("你好"));
        assertEquals(List.of("unchanged"), executed);
        assertEquals("您没有执行此命令的权限~", replies.get(0).getContent());

        executed.clear();
        properties.getCommand().getOwners().put("bot-a", List.of(9L));
        dispatch(messageBuilder("hello").senderId(9L).build());
        assertEquals(List.of("restricted", "unchanged"), executed);
    }

    /**
     * 验证权限覆盖不会绕过会话范围、群禁用或全局禁用
     */
    @Test
    void permissionOverridesPreserveScopeAndDisabledRules() {
        StarBotCommand command = new DefaultIdCommand() {
            @Override
            public CommandScope getScope() {
                return CommandScope.PRIVATE;
            }
        };
        properties.getCommand().getPermissions().put(command.getId(), CommandPermission.EVERYONE);
        var group = context(message("x"));
        assertTrue(policy.evaluate(command, group).isPermissionAllowed());
        assertFalse(policy.evaluate(command, group).isAllowed());
        policy.setGroupDisabled("bot-a", 100L, command.getId(), true);
        assertTrue(policy.evaluate(command, group).isGroupDisabled());
        assertFalse(policy.evaluate(command, group).isAllowed());

        var privateChat = context(messageBuilder("x").type(PushTargetType.FRIEND).build());
        assertTrue(policy.evaluate(command, privateChat).isAllowed());
        properties.getCommand().getDisabled().add(command.getId());
        assertTrue(policy.evaluate(command, privateChat).isGlobalDisabled());
        assertFalse(policy.evaluate(command, privateChat).isAllowed());
    }

    /**
     * 验证机器人所有者权限按平台隔离
     */
    @Test
    void ownerPermissionsAreIsolatedByPlatform() {
        properties.getCommand().getOwners().put("bot-a", List.of(9L));
        properties.getCommand().getOwners().put("bot-b", List.of(8L));
        for (CommandPermission permission : List.of(CommandPermission.OWNER, CommandPermission.ADMIN)) {
            StarBotCommand command = new DefaultIdCommand() {
                @Override
                public CommandPermission getPermission() {
                    return permission;
                }
            };
            for (PushTargetType type : List.of(PushTargetType.FRIEND, PushTargetType.GROUP)) {
                CommandMessage owner = messageBuilder("x").type(type).senderId(9L).build();
                assertTrue(policy.evaluate(command, context(owner)).isAllowed());
                assertFalse(policy.evaluate(command, context(messageBuilder("x")
                        .type(type).senderId(9L).platform("bot-b").build())).isAllowed());
                assertFalse(policy.evaluate(command, context(messageBuilder("x")
                        .type(type).senderId(9L).platform("unconfigured").build())).isAllowed());
                assertTrue(policy.evaluate(command, context(messageBuilder("x")
                        .type(type).platform("bot-b").senderId(8L).build())).isAllowed());
            }
        }
    }

    /**
     * 验证受限命令回复具体原因，全局禁用命令不回复
     */
    @Test
    void restrictedCommandsReplyAndGlobalDisabledCommandsDoNotReply() throws Exception {
        StarBotCommand restricted = new DefaultIdCommand() {
            @Override
            public String getName() {
                return "你好";
            }

            @Override
            public CommandPermission getPermission() {
                return CommandPermission.OWNER;
            }

            @Override
            public CommandScope getScope() {
                return CommandScope.PRIVATE;
            }
        };
        register(List.of(restricted));
        dispatch(message("你好"));
        assertEquals(1, replies.size());
        assertEquals("此命令不适用于当前会话类型~", replies.get(0).getContent());
        replies.clear();
        register(List.of(restricted, command("ok", c -> {
        })));
        dispatch(message("你好"));
        assertEquals(1, replies.size());
        assertEquals("此命令不适用于当前会话类型~", replies.get(0).getContent());
        replies.clear();
        properties.getCommand().getDisabled().add(restricted.getId());
        register(List.of(restricted));
        dispatch(message("你好"));
        assertTrue(replies.isEmpty());
    }

    /**
     * 验证群禁用规则按平台和群隔离，且全局禁用优先
     */
    @Test
    void groupPersistenceIsolationAndGlobalPriority() throws Exception {
        StarBotCommand command = command("persist", c -> {
        });
        assertTrue(policy.setGroupDisabled("bot-a", 100L, "persist", true));
        assertFalse(policy.setGroupDisabled("bot-a", 100L, "persist", true));
        assertTrue(Files.readString(directory.resolve("disabled-command.json")).contains("\"persist\""));
        policy.onApplicationReadyEvent();
        assertTrue(policy.evaluate(command, context(message("你好"))).isGroupDisabled());
        assertFalse(policy.evaluate(command, context(messageBuilder("你好").groupId(101L).build())).isDisabled());
        assertFalse(policy.evaluate(command, context(messageBuilder("你好").platform("bot-b").build())).isDisabled());
        properties.getCommand().getDisabled().add("persist");
        policy.setGroupDisabled("bot-a", 100L, "persist", false);
        assertTrue(policy.evaluate(command, context(message("你好"))).isGlobalDisabled());
    }

    /**
     * 验证损坏的策略文件阻止初始化，重载失败时保留原有内存状态且不改写文件
     */
    @Test
    void corruptFileStopsInitializationWithoutChangingOriginal() throws Exception {
        Path path = directory.resolve("disabled-command.json");
        for (String content : List.of("broken", "null", "{}", "[{}]")) {
            Files.writeString(path, content);
            StarBotCommandPolicy invalid = new StarBotCommandPolicy(properties);
            assertThrows(IllegalStateException.class, invalid::onApplicationReadyEvent);
            assertEquals(content, Files.readString(path));
        }
        policy.setGroupDisabled("bot-a", 100L, "x", true);
        Files.writeString(path, "broken");
        assertThrows(IllegalStateException.class, policy::onApplicationReadyEvent);
        assertTrue(policy.evaluate(command("x", c -> {
        }), context(message("你好"))).isGroupDisabled());
        assertEquals("broken", Files.readString(path));
        var application = new AnnotationConfigApplicationContext();
        try (application) {
            application.registerBean(StarBotCoreProperties.class, () -> properties);
            application.register(StarBotCommandPolicy.class);
            application.refresh();
            assertThrows(IllegalStateException.class, () -> application.publishEvent(
                    new ApplicationReadyEvent(new SpringApplication(), new String[0], application, Duration.ZERO)));
        }
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    /**
     * 验证空白策略文件按空配置加载且不改写原文件
     */
    @Test
    void blankPolicyFilesLoadAsEmptyConfiguration() throws Exception {
        Path path = directory.resolve("disabled-command.json");
        for (String content : List.of("", " ", "\t\r\n ", "\u3000")) {
            Files.writeString(path, content);
            StarBotCommandPolicy empty = new StarBotCommandPolicy(properties);
            empty.onApplicationReadyEvent();
            assertFalse(empty.evaluate(command("x", c -> {
            }), context(message("你好"))).isDisabled());
            assertEquals(content, Files.readString(path));
        }
    }

    /**
     * 验证即时保存失败不回滚当前内存状态
     */
    @Test
    void failedImmediateSavePreservesCurrentMemoryState() throws Exception {
        policy.setGroupDisabled("bot-a", 100L, "x", true);
        Path original = directory.resolve("disabled-command.json");

        Path blocker = directory.resolve("not-a-directory");
        Files.writeString(blocker, "file");
        properties.getCommand().setDisabledCommandPath(blocker.resolve("rules.json").toString());
        policy.setGroupDisabled("bot-a", 100L, "x", false);
        assertFalse(policy.evaluate(command("x", c -> {
        }), context(message("你好"))).isGroupDisabled());
        assertTrue(Files.readString(original).contains("\"x\""));
    }

    /**
     * 验证并发修改群禁用规则后能够完整重新加载
     */
    @Test
    void concurrentGroupUpdatesSurviveReload() throws Exception {
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (long i = 0; i < 20; i++) {
            long group = i;
            writes.add(CompletableFuture.runAsync(
                    () -> policy.setGroupDisabled("bot-a", group, "x", true)));
        }

        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        policy.onApplicationReadyEvent();
        for (long i = 0; i < 20; i++) {
            assertTrue(policy.evaluate(command("x", c -> {
            }), context(messageBuilder("你好").groupId(i).build())).isGroupDisabled());
        }
    }

    /**
     * 验证禁用命令不会执行，群禁用会回复且无匹配消息不会新增回复
     */
    @Test
    void disabledCommandsDoNotExecute() throws Exception {
        List<String> executed = new CopyOnWriteArrayList<>();
        register(List.of(command("a", c -> executed.add("a")), command("b", c -> executed.add("b"))));
        properties.getCommand().getDisabled().add("a");
        dispatch(message("你好"));
        assertEquals(List.of("b"), executed);
        policy.setGroupDisabled("bot-a", 100L, "b", true);
        dispatch(message("你好"));
        assertEquals(List.of("b"), executed);
        assertEquals(1, replies.size());
        assertEquals("此命令已被禁用~", replies.get(0).getContent());
        dispatch(message("unknown"));
        assertEquals(1, replies.size());
    }

    /**
     * 验证规则变更立即保存，空规则使用顶层数组持久化
     */
    @Test
    void changesAreSavedImmediatelyAndEmptyStatePersists() throws Exception {
        Path path = directory.resolve("disabled-command.json");
        policy.setGroupDisabled("bot-a", 100L, "x", true);
        assertTrue(Files.readString(path).contains("\"x\""));
        policy.setGroupDisabled("bot-a", 100L, "x", false);
        assertEquals("[]", Files.readString(path));
        StarBotCommandPolicy restored = new StarBotCommandPolicy(properties);
        restored.onApplicationReadyEvent();
        assertFalse(restored.evaluate(command("x", c -> {
        }), context(message("你好"))).isDisabled());
        assertEquals("[]", Files.readString(path));
    }

    /**
     * 验证 Spring 关闭流程会中断已接受但尚未完成的命令
     */
    @Test
    void springShutdownInterruptsAcceptedCommand() throws Exception {
        Path path = directory.resolve("shutdown.json");
        StarBotCoreProperties config = new StarBotCoreProperties();
        config.getCommand().setDisabledCommandPath(path.toString());
        var application = new AnnotationConfigApplicationContext();
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        application.registerBean(StarBotCoreProperties.class, () -> config);
        application.registerBean("finish", StarBotCommand.class, () -> command("finish", context -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return;
            }
            application.getBean(StarBotCommandPolicy.class)
                    .setGroupDisabled("bot-a", 100L, "saved-on-exit", true);
        }));
        application.register(StarBotCoreThreadPoolConfig.class, StarBotCommandPolicy.class,
                StarBotCommandRegistry.class, StarBotCommandService.class);
        application.refresh();
        try {
            application.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0], application, Duration.ZERO));
            var result = submit(application.getBean(StarBotCommandService.class), message("你好"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var shutdown = CompletableFuture.runAsync(application::close);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            shutdown.get(10, TimeUnit.SECONDS);
            result.get(1, TimeUnit.SECONDS);
            assertFalse(Files.exists(path));
        } finally {
            application.close();
        }
    }

    /**
     * 验证同一会话消息可并发执行，关闭后拒绝新任务
     */
    @Test
    void sameSessionMessagesRunConcurrentlyAndShutdownRejectsNewTasks() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        register(List.of(command("a", c -> {
            if (c.getArguments().equals(List.of("slow"))) {
                started.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            order.add(c.getArguments().get(0));
        })));
        var first = submit(service, message("你好 slow"));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        var next = submit(service, message("你好 next"));
        next.get(3, TimeUnit.SECONDS);
        assertEquals(List.of("next"), order);
        release.countDown();
        first.get(3, TimeUnit.SECONDS);
        assertEquals(List.of("next", "slow"), order);
        service.close();
        assertThrows(ExecutionException.class, () -> dispatch(message("你好")));
    }

    /**
     * 验证命令消息工厂返回空结果时静默完成处理
     */
    @Test
    void emptyMessageIsIgnored() throws Exception {
        List<String> executed = new CopyOnWriteArrayList<>();
        register(List.of(command("a", context -> executed.add("a"))));

        service.dispatch(Optional::empty).get(3, TimeUnit.SECONDS);

        assertTrue(executed.isEmpty());
        assertTrue(replies.isEmpty());
    }

    /**
     * 验证管理命令保护、歧义提示以及全局禁用不可由群命令覆盖
     */
    @Test
    void managerProtectionAmbiguityAndEnableCannotOverrideGlobal() throws Exception {
        var disable = new DisableCommand(registry, policy);
        var enable = new EnableCommand(registry, policy);
        var first = command("a", c -> {
        });
        var second = command("b", c -> {
        });
        register(List.of(disable, enable, first, second));
        var admin = messageBuilder("禁用 你好").role(GroupRole.ADMIN).build();
        dispatch(admin);
        assertTrue(replies.get(0).getContent().contains("多个匹配的命令"));
        dispatch(messageBuilder("禁用 禁用").role(GroupRole.ADMIN).build());
        assertTrue(replies.get(1).getContent().contains("不可禁用"));
        dispatch(messageBuilder("禁用 a").role(GroupRole.ADMIN).build());
        assertTrue(policy.evaluate(first, context(admin)).isGroupDisabled());
        properties.getCommand().getDisabled().add("a");
        dispatch(messageBuilder("启用 a").role(GroupRole.ADMIN).build());
        assertFalse(policy.evaluate(first, context(admin)).isGroupDisabled());
        assertTrue(policy.evaluate(first, context(admin)).isDisabled());
        assertTrue(replies.get(replies.size() - 1).getContent().contains("命令已启用"));
    }

    /**
     * 验证启用和禁用命令仅接受一个参数
     */
    @Test
    void managerCommandsRequireExactlyOneArgument() throws Exception {
        var target = command("a", c -> {
        });
        register(List.of(new DisableCommand(registry, policy), new EnableCommand(registry, policy), target));

        for (String content : List.of("禁用", "禁用 a extra", "启用", "启用 a extra")) {
            dispatch(messageBuilder(content).role(GroupRole.ADMIN).build());
        }

        assertEquals(4, replies.size());
        assertTrue(replies.stream().allMatch(reply -> reply.getContent().startsWith("用法: ")));
        assertFalse(policy.evaluate(target, context(message("你好"))).isDisabled());
    }

    /**
     * 验证上下文回复复用占位符解析、分段和目标路由
     */
    @Test
    void replyReusesPlaceholdersSplitAndRouting() {
        context(message("x")).reply("文本{image_url=https://example.com/a.png}{next}{at=2}");
        assertEquals(2, replies.size());
        assertEquals("bot-a", replies.get(0).getPlatform());
        assertEquals(100L, replies.get(0).getNum());
        assertEquals(PushTargetType.GROUP, replies.get(0).getType());
        assertTrue(replies.get(0).getContent().contains("{image_url="));
        assertSame(replies.get(1), replies.get(0).getNext());
    }

    /**
     * 验证命令配置默认值和外部配置绑定
     */
    @Test
    void configurationDefaultsAndBinding() {
        var defaults = new StarBotCoreProperties();
        assertEquals("disabled-command.json", defaults.getCommand().getDisabledCommandPath());
        assertTrue(defaults.getCommand().getPermissions().isEmpty());
        assertEquals(Integer.MAX_VALUE, defaults.getCommandThread().getQueueCapacity());
        var source = new MapConfigurationPropertySource(Map.of("starbot.core.command.disabled-command-path", "custom.json",
                "starbot.core.command.mention", "true",
                "starbot.core.command-thread.core-pool-size", "3", "starbot.core.command.owners.bot-a[0]", "123",
                "starbot.core.command.permissions.starbot.builtin.status", "OWNER",
                "starbot.core.command.permissions[com.example.HelloCommand]", "ADMIN"));
        new Binder(source).bind("starbot.core", Bindable.ofInstance(defaults));
        assertEquals("custom.json", defaults.getCommand().getDisabledCommandPath());
        assertTrue(defaults.getCommand().isMention());
        assertEquals(3, defaults.getCommandThread().getCorePoolSize());
        assertEquals(List.of(123L), defaults.getCommand().getOwners().get("bot-a"));
        assertEquals(Map.of("starbot.builtin.status", CommandPermission.OWNER,
                "com.example.HelloCommand", CommandPermission.ADMIN), defaults.getCommand().getPermissions());
        assertEquals("command-thread-", executor.getThreadNamePrefix());
    }

    /**
     * 验证无效的命令权限配置会导致绑定失败
     */
    @Test
    void invalidPermissionOverrideFailsBinding() {
        var source = new MapConfigurationPropertySource(Map.of("starbot.core.command.permissions[test.command]", "UNKNOWN"));
        assertThrows(BindException.class, () -> new Binder(source).bind("starbot.core", Bindable.ofInstance(new StarBotCoreProperties())));
    }

    /**
     * 验证线程池饱和时拒绝任务且不在提交线程执行
     */
    @Test
    void boundedExecutorRejectsWithoutRunningOnCaller() throws Exception {
        ThreadPoolTaskExecutor bounded = new ThreadPoolTaskExecutor();
        bounded.setCorePoolSize(1);
        bounded.setMaxPoolSize(1);
        bounded.setQueueCapacity(0);
        bounded.initialize();
        var limited = new StarBotCommandService(bounded, properties, registry, policy);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        register(List.of(command("slow", c -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })));
        try {
            var first = submit(limited, message("你好"));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> submit(limited,
                    messageBuilder("你好").groupId(200L).build()).get(1, TimeUnit.SECONDS));
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            limited.close();
            bounded.shutdown();
        }
    }

    /**
     * 验证 Spring 自动发现依赖注入命令且刷新事件不会重复注册
     */
    @Test
    void springRefreshDiscoversInjectedCommandsAndDoesNotDuplicate() {
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean("greeting", String.class, () -> "依赖已注入");
            spring.register(InjectedCommand.class, StarBotCommandRegistry.class);
            spring.refresh();
            var discovered = spring.getBean(StarBotCommandRegistry.class);
            assertEquals(1, discovered.getCommands().size());
            assertEquals("依赖已注入", discovered.getCommands().get(0).getDescription());
            spring.publishEvent(new ContextRefreshedEvent(spring));
            assertEquals(1, discovered.getCommands().size());
        }
    }

    /**
     * 用于验证 Spring 发现和构造器注入的测试命令
     */
    @StarBotComponent
    public static class InjectedCommand extends DefaultIdCommand {
        private final String greeting;

        public InjectedCommand(String greeting) {
            this.greeting = greeting;
        }

        @Override
        public String getDescription() {
            return greeting;
        }
    }
}
