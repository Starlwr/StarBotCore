package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandMessage;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.message.Message;
import com.starlwr.bot.core.message.PushTargetType;
import com.starlwr.bot.core.message.StarBotMessageSender;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.painter.FontService;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.painter.TextWithStyle;
import com.starlwr.bot.core.plugin.StarBotPlugin;
import com.starlwr.bot.core.plugin.StarBotPluginLoader;
import com.starlwr.bot.core.plugin.StarBotPluginMeta;
import com.starlwr.bot.core.support.system.SystemResourceService;
import com.starlwr.bot.core.support.system.SystemResourceSnapshot.Usage;
import com.starlwr.bot.core.support.system.SystemResourceSnapshot;
import lombok.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 状态命令测试
 * <p>
 * 使用真实字体和 CommonPainter 验证图片回复、内容、长文本扩展及 Logo 开关。
 * 可通过 {@link #SAVE_IMAGE} 将生成的状态图片保存至 TestCommandStatus 目录供人工审阅。
 */
class StatusCommandTest {
    /**
     * 是否将生成的状态图片保存至本地 TestCommandStatus 目录
     * <p>
     * 设为 false 时仅在内存中绘制并验证；设为 true 时额外保存普通版、空列表版、
     * 长文本版、长操作系统名版和数据不可用版图片，并验证图片能够重新读取。
     */
    private static final boolean SAVE_IMAGE = false;

    /**
     * 按 1024 进位换算的 GB 字节数
     */
    private static final long GB = 1024L * 1024 * 1024;

    /**
     * 绘图配置
     */
    private StarBotCoreProperties properties;

    /**
     * 测试构建版本信息
     */
    private BuildProperties build;

    /**
     * 模拟插件加载器
     */
    private StarBotPluginLoader pluginLoader;

    /**
     * 模拟系统资源查询服务
     */
    private SystemResourceService resourceService;

    /**
     * 被测状态命令
     */
    private StatusCommand command;

    /**
     * 真实绘图器接收到的文本，用于验证图片内容
     */
    private final List<String> drawnTexts = new ArrayList<>();

    /**
     * 创建真实绘图组件，并记录实际交给绘图器的文本以验证图片内容
     */
    @BeforeEach
    void setup() {
        properties = new StarBotCoreProperties();
        ReflectionTestUtils.invokeMethod(properties, "init");
        var font = new FontService(new DefaultResourceLoader(), properties);
        ReflectionTestUtils.invokeMethod(font, "init");
        Properties versions = new Properties();
        versions.setProperty("version", "3.0.0");
        build = new BuildProperties(versions);
        pluginLoader = mock(StarBotPluginLoader.class);
        resourceService = mock(SystemResourceService.class);
        when(resourceService.getSnapshot()).thenReturn(resources(new Usage(0.48, 0.08), new Usage(0.12, 0.03),
                32 * GB, Math.round(32 * GB * 0.48), Math.round(32 * GB * 0.08), 8, 16));
        var factory = new StarBotCommonPainterFactory(build, properties, font) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, properties, font, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextWithStyle(@NonNull List<TextWithStyle> texts, Point location, boolean autoWrap, int marginRight) {
                        texts.forEach(text -> drawnTexts.add(text.getText()));
                        return super.drawTextWithStyle(texts, location, autoWrap, marginRight);
                    }
                };
            }
        };
        command = new StatusCommand(build, properties, pluginLoader, factory, resourceService);
    }

    /**
     * 验证未安装插件时回复状态图片，并展示 Core 版本和空列表提示
     */
    @Test
    void rendersVersionAndEmptyPluginState() throws Exception {
        BufferedImage image = render(List.of());

        assertEquals(1200, image.getWidth());
        assertEquals(255, image.getRGB(image.getWidth() / 2, image.getHeight() - 1) >>> 24, "图片底部不应残留透明区域");
        assertTrue(drawnTexts.containsAll(List.of("运行状态", "Core 版本", "v3.0.0", "操作系统", "Windows 11 · 64 位", "暂无已安装插件")));
        assertFalse(drawnTexts.stream().anyMatch(text -> text.startsWith("共 ")));
        saveImage(image, "command-status-empty.png");
    }

    /**
     * 验证状态图片包含插件信息、系统资源及 StarBot 占用
     */
    @Test
    void rendersInstalledPluginInformation() throws Exception {
        BufferedImage image = render(List.of(plugin("OneBot Adapter", "1.2.3", "Alice"),
                plugin("Example Command", "2.0.0", "Bob")));

        assertEquals(1200, image.getWidth());
        assertTrue(drawnTexts.containsAll(List.of("OneBot Adapter", "v1.2.3", "作者：Alice",
                "Example Command", "v2.0.0", "作者：Bob", "系统资源", "48%", "12%", "StarBot 8% (2.56GB)", "StarBot 3%",
                "已用 15.36 GB / 共 32 GB", "8 核 · 16 线程", "磁盘", "64%", "已用 320 GB / 共 500 GB", "StarBot 2% (10GB)",
                "其他", "StarBot", "空闲")));
        saveImage(image, "command-status.png");
    }

    /**
     * 验证长名称、作者和版本号自动换行扩展，关闭 Logo 后正文上移
     */
    @Test
    void expandsForLongPluginInformationAndRespectsLogoSetting() throws Exception {
        List<StarBotPlugin> plugins = IntStream.range(0, 6).mapToObj(index ->
                plugin("超长插件名称测试".repeat(12) + index, "3.0.0-preview-".repeat(6), "作者名称".repeat(12))).toList();
        BufferedImage image = render(plugins);
        assertTrue(image.getHeight() > 3000);
        assertEquals(255, image.getRGB(image.getWidth() / 2, image.getHeight() - 1) >>> 24, "扩展后的图片底部不应透明");

        properties.getPaint().setDrawLogo(false);
        BufferedImage noLogoImage = render(plugins);
        assertEquals(image.getHeight() - 225, noLogoImage.getHeight());
        saveImage(image, "command-status-long.png");
    }

    /**
     * 验证运行时间来自当前进程并随查询更新，顶部装饰跟随 Logo 开关
     */
    @Test
    void rendersCurrentProcessUptimeAndHeaderWithoutLogo() throws Exception {
        var runtime = mock(RuntimeMXBean.class);
        when(runtime.getUptime()).thenReturn(184722000L, 184723000L);
        List<StarBotPlugin> plugins = List.of(plugin("OneBot Adapter", "1.2.3", "Alice"),
                plugin("Example Command", "2.0.0", "Bob"));
        try (var management = mockStatic(ManagementFactory.class)) {
            management.when(ManagementFactory::getRuntimeMXBean).thenReturn(runtime);
            BufferedImage image = render(plugins);
            assertEquals(new java.awt.Color(255, 229, 154).getRGB(), image.getRGB(100, 100));
            assertEquals(new java.awt.Color(252, 203, 224).getRGB(), image.getRGB(1085, 95));
            assertTrue(drawnTexts.containsAll(List.of("运行状态", "运行时间", "2 天 3 小时 18 分 42 秒")));
            assertTrue(drawnTexts.indexOf("操作系统") < drawnTexts.indexOf("运行时间"));
            assertTrue(drawnTexts.indexOf("运行时间") < drawnTexts.indexOf("已安装插件"));
            saveImage(image, "command-status-uptime.png");

            drawnTexts.clear();
            properties.getPaint().setDrawLogo(false);
            BufferedImage noLogoImage = render(plugins);
            assertEquals(image.getHeight() - 225, noLogoImage.getHeight());
            assertEquals(new java.awt.Color(245, 241, 255).getRGB(), noLogoImage.getRGB(100, 70));
            assertEquals(new java.awt.Color(245, 241, 255).getRGB(), noLogoImage.getRGB(1085, 65));
            assertTrue(drawnTexts.containsAll(List.of("StarBot 运行状态", "2 天 3 小时 18 分 43 秒")));
            assertFalse(drawnTexts.contains("运行状态"));
            saveImage(noLogoImage, "command-status-no-logo.png");
        }
    }

    /**
     * 验证运行时间在秒、分、小时和天的边界正确换算，保留中间的零值单位
     */
    @Test
    void formatsUptimeAcrossUnitBoundaries() {
        long[] milliseconds = {0, 999, 1000, 59999, 60000, 3600000, 86400000, 86461000};
        String[] expected = {"0 秒", "0 秒", "1 秒", "59 秒", "1 分 0 秒", "1 小时 0 分 0 秒",
                "1 天 0 小时 0 分 0 秒", "1 天 0 小时 1 分 1 秒"};
        for (int index = 0; index < milliseconds.length; index++) {
            assertEquals(expected[index], ReflectionTestUtils.invokeMethod(command, "formatUptime", milliseconds[index]));
        }
    }

    /**
     * 验证圆环顺时针先紫色再黄色，黄色包含在整机总占用内
     */
    @Test
    void rendersProcessSegmentAfterOtherProcesses() throws Exception {
        var memory = renderDonut("内存", new Usage(0.48, 0.08));
        assertEquals(new java.awt.Color(169, 135, 255).getRGB(), ringColor(memory, 30));
        assertEquals(new java.awt.Color(242, 211, 106).getRGB(), ringColor(memory, 155));
        assertEquals(new java.awt.Color(239, 232, 255).getRGB(), ringColor(memory, 270));

        var cpu = renderDonut("CPU", new Usage(0.12, 0.03));
        assertEquals(new java.awt.Color(169, 135, 255).getRGB(), ringColor(cpu, 20));
        assertEquals(new java.awt.Color(242, 211, 106).getRGB(), ringColor(cpu, 38));
        assertEquals(new java.awt.Color(239, 232, 255).getRGB(), ringColor(cpu, 180));

        var empty = renderDonut("CPU", new Usage(0, 0));
        assertEquals(new java.awt.Color(239, 232, 255).getRGB(), ringColor(empty, 90));
        var full = renderDonut("CPU", new Usage(1, 0.25));
        assertEquals(new java.awt.Color(169, 135, 255).getRGB(), ringColor(full, 180));
        assertEquals(new java.awt.Color(242, 211, 106).getRGB(), ringColor(full, 315));
    }

    /**
     * 验证资源查询不可用时明确提示
     */
    @Test
    void rendersUnavailableResourceState() throws Exception {
        when(resourceService.getSnapshot()).thenReturn(unavailableResources());
        BufferedImage image = render(List.of());
        assertTrue(drawnTexts.containsAll(List.of("—", "StarBot 获取失败", "已用 — / 共 —", "核心数获取失败")));
        assertFalse(drawnTexts.contains("0%"));
        saveImage(image, "command-status-unavailable.png");
    }

    /**
     * 验证较小的进程内存使用 MB 单位，合法零占用与不可用状态能够区分
     */
    @Test
    void rendersSmallMemoryAndValidZeroUsage() throws Exception {
        long mb = 1024L * 1024;
        when(resourceService.getSnapshot()).thenReturn(resources(new Usage(0.48, 0.08), new Usage(0.12, 0.03),
                6400 * mb, 3072 * mb, 512 * mb, 4, 8));
        render(List.of());
        assertTrue(drawnTexts.containsAll(List.of("已用 3 GB / 共 6.25 GB", "StarBot 8% (512MB)", "4 核 · 8 线程")));

        drawnTexts.clear();
        when(resourceService.getSnapshot()).thenReturn(resources(new Usage(0, 0), new Usage(0, 0),
                2 * GB, 0, 0, 2, 4));
        render(List.of());
        assertTrue(drawnTexts.containsAll(List.of("0%", "已用 0 B / 共 2 GB", "StarBot 0% (0B)", "2 核 · 4 线程")));
        assertFalse(drawnTexts.contains("StarBot 获取失败"));
    }

    /**
     * 验证大容量内存和较多核心时，容量单位与百分比保留正确精度
     */
    @Test
    void rendersLargeMemoryAndProcessorCounts() throws Exception {
        when(resourceService.getSnapshot()).thenReturn(resources(new Usage(0.5, 0.125), new Usage(0.12, 0.03),
                2048 * GB, 1024 * GB, 256 * GB, 192, 384));
        render(List.of());
        assertTrue(drawnTexts.containsAll(List.of("已用 1 TB / 共 2 TB", "StarBot 12.5% (256GB)", "192 核 · 384 线程")));
    }

    /**
     * 验证每次命令只查询一次资源，并使用同一份资源快照绘制图片
     */
    @Test
    void queriesResourcesOncePerCommand() throws Exception {
        render(List.of());
        verify(resourceService).getSnapshot();
    }

    /**
     * 验证较长的操作系统名称会扩展独立卡片，不覆盖后面的插件区域
     */
    @Test
    void wrapsLongOperatingSystemName() throws Exception {
        BufferedImage normal = render(List.of());
        String os = "测试操作系统发行版名称 ".repeat(16);
        when(resourceService.getSnapshot()).thenReturn(SystemResourceSnapshot.builder().operatingSystem(os).build());
        BufferedImage expanded = render(List.of());
        assertTrue(expanded.getHeight() > normal.getHeight());
        assertTrue(drawnTexts.contains(os));
        saveImage(expanded, "command-status-long-os.png");
    }

    /**
     * 创建所有资源均获取失败的测试快照
     * @return 所有资源均获取失败的测试快照
     */
    private static SystemResourceSnapshot unavailableResources() {
        return SystemResourceSnapshot.builder().build();
    }

    /**
     * 创建状态图片使用的资源快照，操作系统和磁盘使用固定测试数据
     * @param memory 内存占用率
     * @param cpu CPU 使用率
     * @param memoryTotal 物理内存总字节数
     * @param memoryUsed 已用物理内存字节数
     * @param starBotMemory StarBot 进程物理驻留内存字节数
     * @param physicalProcessors CPU 物理核心数
     * @param logicalProcessors CPU 逻辑处理器数
     * @return 状态图片测试使用的资源快照
     */
    private static SystemResourceSnapshot resources(Usage memory, Usage cpu, long memoryTotal, long memoryUsed,
                                                   long starBotMemory, int physicalProcessors, int logicalProcessors) {
        return SystemResourceSnapshot.builder().operatingSystem("Windows 11 · 64 位")
                .memory(memory).cpu(cpu).disk(new Usage(0.64, 0.02))
                .memoryTotalBytes(memoryTotal).memoryUsedBytes(memoryUsed).starBotMemoryBytes(starBotMemory)
                .physicalProcessorCount(physicalProcessors).logicalProcessorCount(logicalProcessors)
                .diskTotalBytes(500 * GB).diskUsedBytes(320 * GB).starBotDiskBytes(10 * GB).build();
    }

    /**
     * 绘制用于分段颜色断言的环形图
     * @param name 资源名称
     * @param usage 资源占用率
     * @return 环形图图片
     */
    private BufferedImage renderDonut(String name, Usage usage) throws Exception {
        var method = StatusCommand.class.getDeclaredMethod("renderDonut", String.class, Usage.class, int.class, String.class, String.class);
        method.setAccessible(true);
        return (BufferedImage) method.invoke(command, name, usage, 450, "测试资源信息", "StarBot");
    }

    /**
     * 按从顶部顺时针的角度取得圆环中线颜色
     * @param image 环形图
     * @param degrees 角度
     * @return 像素颜色
     */
    private static int ringColor(BufferedImage image, double degrees) {
        double angle = Math.toRadians(degrees);
        return image.getRGB(225 + (int) Math.round(Math.sin(angle) * 102), 140 - (int) Math.round(Math.cos(angle) * 102));
    }

    /**
     * 验证绘图失败时抛出异常
     */
    @Test
    void propagatesRenderingFailure() {
        var factory = mock(StarBotCommonPainterFactory.class);
        when(factory.create(anyInt(), anyInt(), anyBoolean())).thenThrow(new IllegalStateException("测试绘图失败"));
        var sender = mock(StarBotMessageSender.class);
        var status = new StatusCommand(build, properties, pluginLoader, factory, resourceService);

        assertThrows(IllegalStateException.class, () -> status.execute(context(sender)));
        verifyNoInteractions(sender);
    }

    /**
     * 执行状态命令并解码实际回复的 PNG 图片
     * @param plugins 已安装插件
     * @return 状态图片
     */
    private BufferedImage render(List<StarBotPlugin> plugins) throws Exception {
        when(pluginLoader.getPlugins()).thenReturn(plugins);
        var sender = mock(StarBotMessageSender.class);
        command.execute(context(sender));

        ArgumentCaptor<Message> reply = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(reply.capture());
        String content = reply.getValue().getContent();
        String prefix = "{image_base64=";
        assertTrue(content.startsWith(prefix));
        assertTrue(content.endsWith("}"));
        byte[] bytes = Base64.getDecoder().decode(content.substring(prefix.length(), content.length() - 1));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        return image;
    }

    /**
     * 创建状态命令上下文
     * @param sender 消息发送器
     * @return 命令上下文
     */
    private static CommandContext context(StarBotMessageSender sender) {
        CommandMessage message = CommandMessage.builder().platform("test").senderId("1")
                .type(PushTargetType.FRIEND).content("状态").messageSender(sender).build();
        return CommandContext.tryCreate(message, "").orElseThrow();
    }

    /**
     * 根据人工审阅开关保存并验证状态图片
     * @param image 图片
     * @param filename 文件名
     * @throws Exception 创建输出目录或读写图片失败时抛出
     */
    private static void saveImage(BufferedImage image, String filename) throws Exception {
        if (!SAVE_IMAGE) {
            return;
        }

        Path output = Path.of("TestCommandStatus", filename);
        Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(image, "png", output.toFile()));
        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 0);
        assertNotNull(ImageIO.read(output.toFile()));
    }

    /**
     * 创建测试插件
     * @param name 插件名称
     * @param version 插件版本
     * @param author 插件作者
     * @return 测试插件
     */
    private static StarBotPlugin plugin(String name, String version, String author) {
        StarBotPluginMeta meta = new StarBotPluginMeta();
        meta.setName(name);
        meta.setVersion(version);
        meta.setAuthor(author);
        StarBotPlugin plugin = new StarBotPlugin();
        plugin.setMeta(meta);
        return plugin;
    }
}
