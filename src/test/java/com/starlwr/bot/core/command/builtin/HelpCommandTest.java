package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandMessage;
import com.starlwr.bot.core.command.CommandPermission;
import com.starlwr.bot.core.command.CommandScope;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.command.StarBotCommandPolicy;
import com.starlwr.bot.core.command.StarBotCommandRegistry;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.message.PushTargetType;
import com.starlwr.bot.core.painter.FontService;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.painter.TextWithStyle;
import com.starlwr.bot.core.plugin.StarBotPluginLoader;
import com.starlwr.bot.core.support.system.SystemResourceService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命令帮助测试
 * <p>
 * 使用真实字体和绘图组件验证帮助图的状态展示、自动扩展和不透明边界。
 * 可通过 {@link #SAVE_IMAGE} 将生成的帮助图片保存至 TestCommandHelp 目录供人工审阅。
 */
class HelpCommandTest {
    /**
     * 是否将生成的帮助图片保存至本地 TestCommandHelp 目录
     * <p>
     * 设为 false 时仅在内存中绘制并验证；需要人工审阅时手动改为 true，
     * 额外保存生成的图片，并验证文件存在、非空且能够重新读取。
     */
    private static final boolean SAVE_IMAGE = false;

    /**
     * 测试期间使用的临时配置目录
     */
    @TempDir
    private Path directory;

    /**
     * 验证命令状态能够绘制，长描述能够自动扩展图片高度
     */
    @Test
    void rendersCommandStatesAndExpandsForLongText() throws Exception {
        var properties = new StarBotCoreProperties();
        Properties build = new Properties();
        build.setProperty("version", "3.0.0");
        List<String> drawnTexts = new ArrayList<>();
        var factory = recordingFactory(properties, drawnTexts);
        var applicationContext = mock(ApplicationContext.class);
        var registry = new StarBotCommandRegistry(applicationContext);
        properties.getCommand().setDisabledCommandPath(directory.resolve("rules.json").toString());
        var policy = new StarBotCommandPolicy(properties);
        policy.onApplicationReadyEvent();
        var help = new HelpCommand(registry, policy, properties, factory);
        var hello = command("com.example.HelloCommand");
        refresh(registry, applicationContext, List.of(help, new DisableCommand(registry, policy),
                new EnableCommand(registry, policy), new PingCommand(new BuildProperties(build)), new StatusCommand(new BuildProperties(build), properties,
                        mock(StarBotPluginLoader.class), factory, mock(SystemResourceService.class)), hello));
        properties.getCommand().getDisabled().add(hello.getId());
        var message = CommandMessage.builder().platform("bot-a").senderId("2").groupId("100")
                .type(PushTargetType.GROUP).content("帮助").build();
        var context = CommandContext.tryCreate(message, "").orElseThrow();
        var image = decode(render(help, context));
        refresh(registry, applicationContext, registry.getCommands().stream()
                .filter(registered -> !hello.getId().equals(registered.getId())).toList());
        var visibleImage = decode(render(help, context));
        assertEquals(1200, image.getWidth());
        assertEquals(visibleImage.getHeight(), image.getHeight(), "全局禁用命令不应占用帮助图片空间");
        assertTrue(image.getHeight() > 1000);
        assertEquals(255, (image.getRGB(500, image.getHeight() - 1) >>> 24), "底部不应残留透明扩展区域");
        assertTrue(drawnTexts.contains("命令列表"), "开启 Logo 时应使用命令列表标题");
        properties.getPaint().setDrawLogo(false);
        drawnTexts.clear();
        var noLogoImage = decode(render(help, context));
        assertEquals(visibleImage.getHeight() - 225, noLogoImage.getHeight(), "关闭 Logo 后正文应上移");
        assertTrue(drawnTexts.contains("StarBot 命令列表"), "关闭 Logo 时标题应保留 StarBot 文字");
        assertEquals(new Color(245, 241, 255).getRGB(), noLogoImage.getRGB(100, 100), "关闭 Logo 后不应绘制顶部星星");
        properties.getPaint().setDrawLogo(true);
        saveImage(image, "command-help.png");
        saveImage(noLogoImage, "command-help-no-logo.png");

        List<StarBotCommand> longCommands = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int commandIndex = index;
            longCommands.add(new DefaultIdCommand() {
                @Override
                public String getId() {
                    return "test.long." + commandIndex;
                }

                @Override
                public String getDescription() {
                    return "这是一段测试自动换行、绘图高度扩展以及中文字体显示的命令简介。".repeat(30);
                }
            });
        }
        refresh(registry, applicationContext, longCommands);
        var longImage = decode(render(help, context));
        assertTrue(longImage.getHeight() > 3000);
        saveImage(longImage, "command-help-long.png");
    }

    /**
     * 验证两行用法保留左右分区，超过两行后改为底部通栏并保留全部内容
     */
    @Test
    void wrapsUsageAndSwitchesToFullWidthForMoreThanTwoLines() throws Exception {
        var properties = new StarBotCoreProperties();
        properties.getCommand().setPrefix("/");
        List<String> drawnTexts = new ArrayList<>();
        var factory = recordingFactory(properties, drawnTexts);
        var help = new HelpCommand(mock(StarBotCommandRegistry.class), mock(StarBotCommandPolicy.class), properties, factory);
        var oneLine = renderCard(help, previewCommand("查询 <关键词>", "example.query", List.of("查找", "search"), "按关键词查询记录"));
        var twoLines = renderCard(help, previewCommand("查询 <关键词>\n[开始时间] [结束时间]", "example.query", List.of("查找", "search"), "按关键词查询记录"));
        String longUsage = "查询 <关键词>\n[开始时间：YYYY-MM-DD HH:mm:ss]\n[结束时间：YYYY-MM-DD HH:mm:ss]";
        drawnTexts.clear();
        var fullWidth = renderCard(help, previewCommand(longUsage, "example.query", List.of("查找", "search"), "按关键词查询记录"));

        assertEquals(1100, oneLine.getWidth());
        assertEquals(1, horizontalDividers(oneLine));
        assertEquals(1, horizontalDividers(twoLines));
        assertEquals(2, horizontalDividers(fullWidth), "通栏用法应与别名以第二条横线分隔");
        assertTrue(twoLines.getHeight() > oneLine.getHeight());
        assertTrue(fullWidth.getHeight() > twoLines.getHeight());
        String content = String.join("", drawnTexts);
        assertTrue(content.contains("example.query"), "卡片应展示命令 ID");
        assertTrue(content.contains("/" + longUsage.replace("\n", "")), "换行后应完整展示带前缀的用法");
        saveImage(oneLine, "command-help-usage-short.png");
        saveImage(twoLines, "command-help-usage-two-lines.png");
        saveImage(fullWidth, "command-help-usage-full-width.png");
    }

    /**
     * 验证自动换行、长 ID、长别名及包含补充字符的单个长参数不会丢失内容
     */
    @Test
    void preservesLongIdsAliasesAndUnicodeParameters() throws Exception {
        var properties = new StarBotCoreProperties();
        List<String> drawnTexts = new ArrayList<>();
        var factory = recordingFactory(properties, drawnTexts);
        var help = new HelpCommand(mock(StarBotCommandRegistry.class), mock(StarBotCommandPolicy.class), properties, factory);
        String id = "com.example." + "LongCommand".repeat(20);
        String alias = "这是一个很长的命令别名".repeat(10);
        String usage = "查询 <" + "长参数😀".repeat(35) + ">";
        var image = renderCard(help, previewCommand(usage, id, List.of(alias, "search"), "这是一段较长的命令简介。".repeat(12)));

        String content = String.join("", drawnTexts);
        assertTrue(content.contains(id), "长 ID 换行后应完整保留");
        assertTrue(content.contains(alias), "长别名标签换行后应完整保留");
        assertTrue(content.contains(usage), "单个长参数按码点换行后应完整保留");
        assertEquals(2, horizontalDividers(image), "自动测量超过两行时也应使用通栏用法");
        assertTrue(image.getHeight() > 500);
        assertEquals(255, image.getRGB(image.getWidth() / 2, image.getHeight() - 1) >>> 24);
        saveImage(image, "command-help-long-fields.png");
    }

    /**
     * 验证帮助菜单只输出约定的五种状态，并遵循禁用、范围、权限的优先级
     */
    @Test
    void mapsPolicyDecisionToDisplayState() {
        var help = new HelpCommand(mock(StarBotCommandRegistry.class), mock(StarBotCommandPolicy.class),
                new StarBotCoreProperties(), mock(StarBotCommonPainterFactory.class));
        assertEquals("可用", stateText(state(help, CommandScope.ALL, decision(false, false, true, true))));
        assertEquals("禁用", stateText(state(help, CommandScope.ALL, decision(false, true, true, true))));
        assertEquals("权限不足", stateText(state(help, CommandScope.ALL, decision(false, false, true, false))));
        Object privateOnly = state(help, CommandScope.PRIVATE, decision(false, false, false, true));
        Object groupOnly = state(help, CommandScope.GROUP, decision(false, false, false, true));
        Object permissionDenied = state(help, CommandScope.ALL, decision(false, false, true, false));
        assertEquals("仅私聊可用", stateText(privateOnly));
        assertEquals("仅群聊可用", stateText(groupOnly));
        assertEquals("禁用", stateText(state(help, CommandScope.PRIVATE, decision(false, true, false, false))));
        assertEquals(stateColor(permissionDenied), stateColor(privateOnly));
        assertEquals(stateColor(permissionDenied), stateColor(groupOnly));
    }

    /**
     * 验证帮助菜单使用配置覆盖后的权限展示命令状态
     */
    @Test
    void commandViewsUsePermissionOverrides() {
        var properties = new StarBotCoreProperties();
        properties.getCommand().setDisabledCommandPath(directory.resolve("rules.json").toString());
        var policy = new StarBotCommandPolicy(properties);
        policy.onApplicationReadyEvent();
        var applicationContext = mock(ApplicationContext.class);
        var registry = new StarBotCommandRegistry(applicationContext);
        var hello = command("test.hello");
        refresh(registry, applicationContext, List.of(hello));
        var help = new HelpCommand(registry, policy, properties, mock(StarBotCommonPainterFactory.class));
        var message = CommandMessage.builder().platform("bot-a").senderId("2").groupId("100")
                .type(PushTargetType.GROUP).content("帮助").build();
        var context = CommandContext.tryCreate(message, "").orElseThrow();

        for (CommandPermission permission : List.of(CommandPermission.OWNER, CommandPermission.EVERYONE)) {
            properties.getCommand().getPermissions().put(hello.getId(), permission);
            List<?> views = ReflectionTestUtils.invokeMethod(help, "commandViews", context);
            assertNotNull(views);
            assertEquals(1, views.size());
            Object state = ReflectionTestUtils.invokeMethod(views.get(0), "getState");
            assertEquals(permission == CommandPermission.OWNER ? "权限不足" : "可用", stateText(state));
        }
    }

    /**
     * 验证绘图失败时直接向上传播异常，不发送降级文本
     */
    @Test
    void propagatesRenderingFailure() {
        var properties = new StarBotCoreProperties();
        var applicationContext = mock(ApplicationContext.class);
        var registry = new StarBotCommandRegistry(applicationContext);
        properties.getCommand().setDisabledCommandPath(directory.resolve("rules.json").toString());
        var policy = new StarBotCommandPolicy(properties);
        policy.onApplicationReadyEvent();
        var factory = mock(StarBotCommonPainterFactory.class);
        when(factory.create(anyInt(), anyInt(), anyBoolean())).thenThrow(new IllegalStateException("测试绘图失败"));
        var help = new HelpCommand(registry, policy, properties, factory);
        refresh(registry, applicationContext, List.of(help));
        var message = CommandMessage.builder().platform("bot-a").senderId("2").type(PushTargetType.FRIEND).content("帮助").build();
        var context = CommandContext.tryCreate(message, "").orElseThrow();
        assertThrows(IllegalStateException.class, () -> help.execute(context));
    }

    /**
     * 根据人工审阅开关保存并验证帮助图片
     *
     * @param image 帮助图片
     * @param filename 文件名
     */
    private static void saveImage(BufferedImage image, String filename) throws Exception {
        if (!SAVE_IMAGE) {
            return;
        }
        Path output = Path.of("TestCommandHelp", filename);
        Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(image, "png", output.toFile()));
        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 0);
        assertNotNull(ImageIO.read(output.toFile()));
    }

    /**
     * 模拟 Spring 发现命令并刷新注册表
     *
     * @param registry 命令注册表
     * @param applicationContext Spring 容器
     * @param discovered 已发现的命令
     */
    private static void refresh(StarBotCommandRegistry registry, ApplicationContext applicationContext,
                                Collection<StarBotCommand> discovered) {
        Map<String, StarBotCommand> beans = new LinkedHashMap<>();
        int index = 0;
        for (StarBotCommand command : discovered) {
            beans.put("command-" + index++, command);
        }
        when(applicationContext.getBeansOfType(StarBotCommand.class)).thenReturn(beans);
        registry.onContextRefreshedEvent();
    }

    /**
     * 将帮助图片 Base64 解码为图片以供断言
     *
     * @param base64 帮助图片 Base64
     * @return 解码后的图片
     */
    private static BufferedImage decode(Optional<String> base64) throws Exception {
        assertTrue(base64.isPresent());
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64.orElseThrow())));
        assertNotNull(image);
        return image;
    }

    /**
     * 调用私有绘图入口
     */
    @SuppressWarnings("unchecked")
    private static Optional<String> render(HelpCommand help, CommandContext context) {
        try {
            var method = HelpCommand.class.getDeclaredMethod("render", CommandContext.class);
            method.setAccessible(true);
            return (Optional<String>) method.invoke(help, context);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("调用帮助图片绘制方法失败", e);
        }
    }

    /**
     * 创建真实绘图器并记录实际绘制的文字，验证换行不会丢失内容
     */
    private static StarBotCommonPainterFactory recordingFactory(StarBotCoreProperties properties, List<String> drawnTexts) {
        ReflectionTestUtils.invokeMethod(properties, "init");
        var font = new FontService(new DefaultResourceLoader(), properties);
        ReflectionTestUtils.invokeMethod(font, "init");
        Properties versions = new Properties();
        versions.setProperty("version", "3.0.0");
        var build = new BuildProperties(versions);
        return new StarBotCommonPainterFactory(build, properties, font) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, properties, font, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextWithStyle(@NotNull List<TextWithStyle> texts, Point location, boolean autoWrap, int marginRight) {
                        texts.forEach(text -> drawnTexts.add(text.getText()));
                        return super.drawTextWithStyle(texts, location, autoWrap, marginRight);
                    }
                };
            }
        };
    }

    /**
     * 绘制单张可用命令卡片
     */
    private static BufferedImage renderCard(HelpCommand help, StarBotCommand command) {
        BufferedImage image = ReflectionTestUtils.invokeMethod(help, "renderCommand", command,
                state(help, CommandScope.ALL, decision(false, false, true, true)));
        assertNotNull(image);
        return image;
    }

    /**
     * 统计横向分隔线，用于区分左右分区与底部通栏的实际绘图结果
     */
    private static int horizontalDividers(BufferedImage image) {
        int color = new Color(237, 230, 247).getRGB();
        int count = 0;
        boolean previous = false;
        for (int y = 0; y < image.getHeight(); y++) {
            boolean current = image.getRGB(image.getWidth() / 4, y) == color;
            if (current && !previous) {
                count++;
            }
            previous = current;
        }
        return count;
    }

    /**
     * 创建具有指定展示内容的测试命令
     */
    private static StarBotCommand previewCommand(String usage, String id, List<String> aliases, String description) {
        return new DefaultIdCommand() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getName() {
                return "查询";
            }

            @Override
            public List<String> getAliases() {
                return aliases;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String getUsage() {
                return usage;
            }
        };
    }

    /**
     * 创建普通测试命令
     */
    private static StarBotCommand command(String id) {
        return new StarBotCommand() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getName() {
                return "你好";
            }

            @Override
            public List<String> getAliases() {
                return List.of("hello");
            }

            @Override
            public String getDescription() {
                return "测试";
            }

            @Override
            public void execute(CommandContext context) {
            }
        };
    }

    /**
     * 获取指定范围命令的展示状态
     */
    private static Object state(HelpCommand help, CommandScope scope, StarBotCommandPolicy.Decision decision) {
        StarBotCommand command = new DefaultIdCommand() {
            @Override
            public CommandScope getScope() {
                return scope;
            }
        };
        try {
            var method = HelpCommand.class.getDeclaredMethod("state", StarBotCommand.class,
                    StarBotCommandPolicy.Decision.class);
            method.setAccessible(true);
            return method.invoke(help, command, decision);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("调用状态映射方法失败", e);
        }
    }

    /**
     * 获取私有帮助状态的展示文字
     */
    private static String stateText(Object state) {
        return stateValue(state, "getText", String.class);
    }

    /**
     * 获取私有帮助状态的展示颜色
     */
    private static Color stateColor(Object state) {
        return stateValue(state, "getColor", Color.class);
    }

    /**
     * 调用私有帮助状态的无参方法
     */
    private static <T> T stateValue(Object state, String methodName, Class<T> type) {
        try {
            var method = state.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return type.cast(method.invoke(state));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读取帮助状态失败", e);
        }
    }

    /**
     * 创建策略结果
     */
    private static StarBotCommandPolicy.Decision decision(boolean globalDisabled, boolean groupDisabled,
                                                           boolean scopeAllowed, boolean permissionAllowed) {
        try {
            var constructor = StarBotCommandPolicy.Decision.class.getDeclaredConstructor(
                    boolean.class, boolean.class, boolean.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(globalDisabled, groupDisabled, scopeAllowed, permissionAllowed);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("创建命令策略结果失败", e);
        }
    }

    /**
     * 使用接口默认 ID 的测试命令
     */
    private static class DefaultIdCommand implements StarBotCommand {
        @Override
        public String getName() {
            return "default";
        }

        @Override
        public String getDescription() {
            return "default";
        }

        @Override
        public void execute(CommandContext context) {
        }
    }
}
