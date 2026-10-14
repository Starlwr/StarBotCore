package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.painter.TextWithStyle;
import com.starlwr.bot.core.plugin.StarBotPlugin;
import com.starlwr.bot.core.plugin.StarBotPluginLoader;
import com.starlwr.bot.core.plugin.StarBotPluginMeta;
import com.starlwr.bot.core.support.system.SystemResourceService;
import com.starlwr.bot.core.support.system.SystemResourceSnapshot.Usage;
import com.starlwr.bot.core.support.system.SystemResourceSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 状态命令
 */
@Slf4j
@Component
public class StatusCommand implements StarBotCommand {
    private static final int WIDTH = 1200;

    private static final int MARGIN = 50;

    private static final int PADDING = 32;

    private static final Color COLOR_DEEP_PURPLE = new Color(78, 45, 128);

    private static final Color COLOR_ACCENT = new Color(121, 86, 237);

    private static final Color COLOR_BACKGROUND = new Color(245, 241, 255);

    private static final Color COLOR_OVERVIEW = new Color(240, 234, 253);

    private static final Color COLOR_BORDER = new Color(207, 188, 250);

    private static final Color COLOR_OTHER = new Color(169, 135, 255);

    private static final Color COLOR_STARBOT = new Color(242, 211, 106);

    private static final Color COLOR_FREE = new Color(239, 232, 255);

    private static final Color COLOR_STAR = new Color(255, 229, 154);

    private static final Color COLOR_DECORATION_PURPLE = new Color(173, 124, 248);

    private static final Color COLOR_DECORATION_PINK = new Color(252, 203, 224);

    private final BuildProperties build;

    private final StarBotCoreProperties properties;

    private final StarBotPluginLoader pluginLoader;

    private final StarBotCommonPainterFactory factory;

    private final SystemResourceService resourceService;

    private final ResourceLoader resourceLoader;

    @Autowired
    public StatusCommand(BuildProperties build, StarBotCoreProperties properties, StarBotPluginLoader pluginLoader, StarBotCommonPainterFactory factory, SystemResourceService resourceService) {
        this.build = build;
        this.properties = properties;
        this.pluginLoader = pluginLoader;
        this.factory = factory;
        this.resourceService = resourceService;
        this.resourceLoader = new DefaultResourceLoader(getClass().getClassLoader());
    }

    @Override
    public String getId() {
        return "starbot.builtin.status";
    }

    @Override
    public String getName() {
        return "状态";
    }

    @Override
    public List<String> getAliases() {
        return List.of("status");
    }

    @Override
    public String getDescription() {
        return "查看 StarBot 运行状态";
    }

    @Override
    public int getArgumentCount() {
        return 0;
    }

    @Override
    public void execute(CommandContext context) {
        Optional<String> optionalBase64 = render();
        if (optionalBase64.isPresent()) {
            context.reply("{image_base64=" + optionalBase64.get() + "}");
        } else {
            log.error("生成运行状态图片失败");
        }
    }

    /**
     * 绘制运行状态图片
     * @return 运行状态图片的 Base64 字符串
     */
    private Optional<String> render() {
        SystemResourceSnapshot snapshot = resourceService.getSnapshot();
        List<StarBotPlugin> plugins = pluginLoader.getPlugins();
        CommonPainter painter = factory.create(WIDTH, 1200, true).setPos(MARGIN, 50);
        drawLogo(painter);
        drawHeaderDecoration(painter);
        drawTitle(painter);
        drawCoreVersion(painter);
        drawOverviewCard(painter, "操作系统", snapshot.getOperatingSystem());
        drawOverviewCard(painter, "运行时间", formatUptime(ManagementFactory.getRuntimeMXBean().getUptime()));
        painter.movePos(0, 12);
        drawSectionTitle(painter, "已安装插件");

        if (CollectionUtils.isEmpty(plugins)) {
            painter.setPos(MARGIN + PADDING, painter.getY());
            painter.drawTextWithStyle(List.of(new TextWithStyle("暂无已安装插件", 30, Color.GRAY)));
            painter.movePos(0, 25);
        } else {
            for (StarBotPlugin plugin : plugins) {
                drawPlugin(painter, plugin.getMeta());
            }
        }

        painter.movePos(0, 25);
        drawSectionTitle(painter, "系统资源");
        drawSystemResources(painter, snapshot);
        drawFooter(painter);

        return painter.createSolidRoundedRectangleBackground(COLOR_BACKGROUND, 35).base64();
    }

    /**
     * 绘制 StarBot Logo
     * @param painter 绘图器
     */
    private void drawLogo(CommonPainter painter) {
        if (!properties.getPaint().isDrawLogo()) {
            return;
        }

        try (InputStream input = resourceLoader.getResource("classpath:logo.png").getInputStream()) {
            BufferedImage logo = ImageIO.read(input);
            if (logo != null) {
                painter.drawImage(logo, new Point((WIDTH - logo.getWidth()) / 2, 55)).setPos(MARGIN, 275);
            }
        } catch (IOException e) {
            log.error("加载 StarBot Logo 失败", e);
        }
    }

    /**
     * 绘制顶部装饰
     * @param painter 绘图器
     */
    private void drawHeaderDecoration(CommonPainter painter) {
        if (!properties.getPaint().isDrawLogo()) {
            return;
        }

        painter.drawStar(100, 100, 26, COLOR_STAR);
        painter.drawStar(WIDTH - 105, 175, 22, COLOR_STAR);
        painter.drawStar(90, 205, 15, Color.WHITE);
        painter.drawStar(WIDTH - 80, 85, 14, Color.WHITE);
        painter.drawRoundedRectangle(130, 160, 20, 20, 20, COLOR_BORDER);
        painter.drawRoundedRectangle(WIDTH - 125, 85, 20, 20, 20, COLOR_DECORATION_PINK);
    }

    /**
     * 水平居中绘制含格式文本
     * @param painter 绘图器
     * @param text 文本
     * @param centerX 中心 X 坐标
     * @param y 顶部 Y 坐标
     */
    private void drawCenteredText(CommonPainter painter, TextWithStyle text, int centerX, int y) {
        int width = painter.getStringWidthAndHeight(text).getLeft();
        painter.drawTextWithStyle(List.of(text), new Point(centerX - width / 2, y));
    }

    /**
     * 绘制运行状态标题
     * @param painter 绘图器
     */
    private void drawTitle(CommonPainter painter) {
        int y = painter.getY();
        String text = properties.getPaint().isDrawLogo() ? "运行状态" : "StarBot 运行状态";
        TextWithStyle title = new TextWithStyle(text, 72, COLOR_DEEP_PURPLE, Font.BOLD);
        Pair<Integer, Integer> size = painter.getStringWidthAndHeight(title);
        int titleHeight = size.getRight();
        int decorationY = y + titleHeight / 2;
        drawTitleDecoration(painter, (WIDTH - size.getLeft()) / 2 - 55, decorationY, -1);
        drawTitleDecoration(painter, (WIDTH + size.getLeft()) / 2 + 55, decorationY, 1);
        drawCenteredText(painter, title, WIDTH / 2, y);
        painter.setPos(MARGIN, y + titleHeight + 34);
    }

    /**
     * 绘制标题两侧的装饰星星和短线
     * @param painter 绘图器
     * @param x 星星中心 X 坐标
     * @param y 星星中心 Y 坐标
     * @param direction 装饰朝向，左侧为 -1，右侧为 1
     */
    private void drawTitleDecoration(CommonPainter painter, int x, int y, int direction) {
        painter.drawStar(x, y, 28, COLOR_STAR);
        drawDecorationRay(painter, x + direction * 45, y - 18, x + direction * 58, y - 29);
        drawDecorationRay(painter, x + direction * 47, y + 12, x + direction * 62, y + 17);
    }

    /**
     * 绘制装饰紫色短线
     * @param painter 绘图器
     * @param startX 起点 X 坐标
     * @param startY 起点 Y 坐标
     * @param endX 终点 X 坐标
     * @param endY 终点 Y 坐标
     */
    private void drawDecorationRay(CommonPainter painter, int startX, int startY, int endX, int endY) {
        double length = Math.hypot(endX - startX, endY - startY);
        int offsetX = (int) Math.round((endY - startY) / length * 4);
        int offsetY = (int) Math.round((startX - endX) / length * 4);
        painter.drawPolygon(List.of(new Point(startX + offsetX, startY + offsetY), new Point(endX + offsetX, endY + offsetY),
                new Point(endX - offsetX, endY - offsetY), new Point(startX - offsetX, startY - offsetY)), COLOR_DECORATION_PURPLE);
        painter.drawRoundedRectangle(startX - 4, startY - 4, 8, 8, 8, COLOR_DECORATION_PURPLE);
        painter.drawRoundedRectangle(endX - 4, endY - 4, 8, 8, 8, COLOR_DECORATION_PURPLE);
    }

    /**
     * 绘制 Core 版本卡片
     * @param painter 绘图器
     */
    private void drawCoreVersion(CommonPainter painter) {
        int width = WIDTH - MARGIN * 2;
        TextWithStyle label = new TextWithStyle("Core 版本", 36, COLOR_DEEP_PURPLE, Font.BOLD);
        TextWithStyle value = new TextWithStyle("v" + build.getVersion(), 64, COLOR_DEEP_PURPLE, Font.BOLD);
        int availableWidth = width - PADDING * 2 - painter.getStringWidthAndHeight(label).getLeft() - 32;
        int versionWidth = Math.min(availableWidth, painter.getStringWidthAndHeight(value).getLeft() + 1);
        CommonPainter version = factory.create(versionWidth, 120, true).setRowSpace(0);
        version.drawTextWithStyle(List.of(value), null, true, 0);
        BufferedImage versionImage = version.createSolidRoundedRectangleBackground(new Color(0, 0, 0, 0), 0).getBufferedImage();

        int height = Math.max(144, versionImage.getHeight() + 48);
        CommonPainter card = factory.create(width, height, true);
        int labelHeight = card.getStringWidthAndHeight(label).getRight();
        card.drawTextWithStyle(List.of(label), new Point(PADDING, (height - labelHeight) / 2));
        card.drawImage(versionImage, new Point(width - PADDING - versionImage.getWidth(), (height - versionImage.getHeight()) / 2));
        card.setPos(0, height);

        int y = painter.getY();
        painter.drawImageWithBorder(card.createSolidRoundedRectangleBackground(COLOR_OVERVIEW, 32).getBufferedImage(), new Point(MARGIN, y), COLOR_BORDER, 32, 3);
        painter.setPos(MARGIN, y + height + 22);
    }

    /**
     * 绘制信息卡片
     * @param painter 绘图器
     * @param name 信息名称
     * @param text 信息内容
     */
    private void drawOverviewCard(CommonPainter painter, String name, String text) {
        int width = WIDTH - MARGIN * 2;
        TextWithStyle label = new TextWithStyle(name, 32, COLOR_DEEP_PURPLE, Font.BOLD);
        TextWithStyle value = new TextWithStyle(text, 30, COLOR_DEEP_PURPLE);
        int availableWidth = width - PADDING * 2 - painter.getStringWidthAndHeight(label).getLeft() - 32;
        int valueWidth = Math.min(availableWidth, painter.getStringWidthAndHeight(value).getLeft() + 1);
        CommonPainter content = factory.create(valueWidth, 80, true).setRowSpace(0);
        content.drawTextWithStyle(List.of(value), null, true, 0);
        BufferedImage valueImage = content.createSolidRoundedRectangleBackground(new Color(0, 0, 0, 0), 0).getBufferedImage();

        int height = Math.max(112, valueImage.getHeight() + 40);
        CommonPainter card = factory.create(width, height, true);
        int labelHeight = card.getStringWidthAndHeight(label).getRight();
        card.drawTextWithStyle(List.of(label), new Point(PADDING, (height - labelHeight) / 2));
        card.drawImage(valueImage, new Point(width - PADDING - valueImage.getWidth(), (height - valueImage.getHeight()) / 2));
        card.setPos(0, height);

        int y = painter.getY();
        painter.drawImageWithBorder(card.createSolidRoundedRectangleBackground(COLOR_OVERVIEW, 32).getBufferedImage(), new Point(MARGIN, y), COLOR_BORDER, 32, 3);
        painter.setPos(MARGIN, y + height + 22);
    }

    /**
     * 格式化本次进程运行时间
     * @param milliseconds 进程运行毫秒数
     * @return 本次进程运行时间
     */
    private String formatUptime(long milliseconds) {
        Duration uptime = Duration.ofMillis(milliseconds);
        StringBuilder text = new StringBuilder();
        if (uptime.toDays() > 0) {
            text.append(uptime.toDays()).append(" 天 ");
        }
        if (uptime.toHours() > 0) {
            text.append(uptime.toHoursPart()).append(" 小时 ");
        }
        if (uptime.toMinutes() > 0) {
            text.append(uptime.toMinutesPart()).append(" 分 ");
        }
        return text.append(uptime.toSecondsPart()).append(" 秒").toString();
    }

    /**
     * 居中绘制章节标题和左右对称分隔线
     * @param painter 绘图器
     * @param text 标题
     */
    private void drawSectionTitle(CommonPainter painter, String text) {
        int y = painter.getY();
        TextWithStyle title = new TextWithStyle(text, 40, COLOR_DEEP_PURPLE, Font.BOLD);
        Pair<Integer, Integer> size = painter.getStringWidthAndHeight(title);
        int lineWidth = (WIDTH - size.getLeft()) / 2 - MARGIN - 30;
        int lineY = y + size.getRight() / 2;
        painter.drawRoundedRectangle(MARGIN, lineY, lineWidth, 2, 2, COLOR_BORDER);
        painter.drawRoundedRectangle(WIDTH - MARGIN - lineWidth, lineY, lineWidth, 2, 2, COLOR_BORDER);
        drawCenteredText(painter, title, WIDTH / 2, y);
        painter.setPos(MARGIN, y + 76);
    }

    /**
     * 绘制插件卡片
     * @param painter 绘图器
     * @param meta 插件元数据
     */
    private void drawPlugin(CommonPainter painter, StarBotPluginMeta meta) {
        BufferedImage badge = renderVersion(painter, meta.getVersion());
        int contentWidth = WIDTH - (MARGIN + PADDING) * 2 - badge.getWidth() - 24;
        CommonPainter content = factory.create(contentWidth, 160, true);
        content.drawTextWithStyle(List.of(new TextWithStyle(meta.getName(), 36, COLOR_DEEP_PURPLE, Font.BOLD)), null, true, 0);
        content.drawTextWithStyle(List.of(new TextWithStyle("作者：" + meta.getAuthor(), 28, Color.GRAY)), null, true, 0);
        content.setPos(null, Math.max(1, content.getY() - content.getRowSpace()));
        BufferedImage image = content.createSolidRoundedRectangleBackground(new Color(0, 0, 0, 0), 0).getBufferedImage();

        int width = WIDTH - MARGIN * 2;
        int height = Math.max(image.getHeight(), badge.getHeight() + 3) + PADDING * 2;
        CommonPainter card = factory.create(width, height, true);
        card.drawImage(image, new Point(PADDING, PADDING));
        card.drawImageWithBorder(badge, new Point(width - PADDING - badge.getWidth(), PADDING + 3), COLOR_BORDER, 40, 2);
        card.setPos(0, height);

        int y = painter.getY();
        painter.drawImageWithBorder(card.createSolidRoundedRectangleBackground(Color.WHITE, 32).getBufferedImage(), new Point(MARGIN, y), COLOR_BORDER, 32, 3);
        painter.setPos(MARGIN, y + height + 22);
    }

    /**
     * 绘制插件版本标签
     * @param painter 绘图器
     * @param version 版本号
     * @return 插件版本标签图片
     */
    private BufferedImage renderVersion(CommonPainter painter, String version) {
        String text = "v" + version;
        int width = Math.min(400, painter.getStringWidthAndHeight(text, 32).getLeft() + 48);
        CommonPainter badge = factory.create(width, 80, true).setPos(24, 10).setRowSpace(0);
        badge.drawTextWithStyle(List.of(new TextWithStyle(text, 32, COLOR_ACCENT)), null, true, 24);
        badge.movePos(0, 10);
        return badge.createSolidRoundedRectangleBackground(Color.WHITE, 40).getBufferedImage();
    }

    /**
     * 绘制系统资源卡片
     * @param painter 绘图器
     * @param snapshot 系统资源快照
     */
    private void drawSystemResources(CommonPainter painter, SystemResourceSnapshot snapshot) {
        int width = WIDTH - MARGIN * 2;
        String memoryDetails = formatCapacityDetails(snapshot.getMemoryUsedBytes(), snapshot.getMemoryTotalBytes());
        String memoryProcess = formatCapacityUsage(snapshot.getMemory(), snapshot.getStarBotMemoryBytes());
        String diskDetails = formatCapacityDetails(snapshot.getDiskUsedBytes(), snapshot.getDiskTotalBytes());
        String diskProcess = formatCapacityUsage(snapshot.getDisk(), snapshot.getStarBotDiskBytes());
        String cpuDetails = snapshot.getPhysicalProcessorCount() > 0 || snapshot.getLogicalProcessorCount() > 0
                ? (snapshot.getPhysicalProcessorCount() > 0 ? snapshot.getPhysicalProcessorCount() : "—") + " 核 · "
                + (snapshot.getLogicalProcessorCount() > 0 ? snapshot.getLogicalProcessorCount() : "—") + " 线程"
                : "核心数获取失败";

        CommonPainter card = factory.create(width, 500, true);
        int columnWidth = width / 3;
        card.drawImage(renderDonut("内存", snapshot.getMemory(), columnWidth, memoryDetails, memoryProcess), new Point(0, 16));
        card.drawImage(renderDonut("CPU", snapshot.getCpu(), columnWidth, cpuDetails, formatProcessUsage(snapshot.getCpu())), new Point(columnWidth, 16));
        card.drawImage(renderDonut("磁盘", snapshot.getDisk(), columnWidth, diskDetails, diskProcess), new Point(columnWidth * 2, 16));
        card.drawRectangle(PADDING, 428, width - PADDING * 2, 2, COLOR_BORDER);
        drawLegend(card, width / 2 - 220, "其他", COLOR_OTHER);
        drawLegend(card, width / 2, "StarBot", COLOR_STARBOT);
        drawLegend(card, width / 2 + 220, "空闲", COLOR_FREE);
        card.setPos(0, 500);

        int y = painter.getY();
        painter.drawImageWithBorder(card.createSolidRoundedRectangleBackground(Color.WHITE, 32).getBufferedImage(), new Point(MARGIN, y), COLOR_BORDER, 32, 3);
        painter.setPos(MARGIN, y + 500);
    }

    /**
     * 绘制系统资源环形图
     * @param name 资源名称
     * @param usage 资源占用率
     * @param width 图片宽度
     * @param details 详情文本
     * @param process StarBot 占用文字
     * @return 系统资源环形图图片
     */
    private BufferedImage renderDonut(String name, Usage usage, int width, String details, String process) {
        CommonPainter painter = factory.create(width, 399, true);
        int x = (width - 240) / 2;
        painter.drawRingSegment(x, 20, 240, 36, 90, -360, COLOR_FREE);
        if (usage.isAvailable()) {
            double otherAngle = usage.getOther() * 360;
            painter.drawRingSegment(x, 20, 240, 36, 90, -otherAngle, COLOR_OTHER);
            painter.drawRingSegment(x, 20, 240, 36, 90 - otherAngle, -usage.getStarBot() * 360, COLOR_STARBOT);
        }

        TextWithStyle percentage = new TextWithStyle(formatPercentage(usage.getSystem()), 48, COLOR_DEEP_PURPLE, Font.BOLD);
        int textHeight = painter.getStringWidthAndHeight(percentage).getRight();
        drawCenteredText(painter, percentage, width / 2, 140 - textHeight / 2);
        drawCenteredText(painter, new TextWithStyle(name, 32, COLOR_DEEP_PURPLE, Font.BOLD), width / 2, 270);
        drawResourceText(painter, details, 20, new Color(132, 122, 151), width, 316);
        drawResourceText(painter, process, 22, COLOR_DEEP_PURPLE, width, 358);
        painter.setPos(0, 399);
        return painter.createSolidRoundedRectangleBackground(new Color(0, 0, 0, 0), 0).getBufferedImage();
    }

    /**
     * 按列宽缩小并居中绘制说明文字，避免文字重叠
     * @param painter 绘图器
     * @param text 说明文字
     * @param fontSize 初始字体大小
     * @param color 文字颜色
     * @param width 当前列宽度
     * @param y 文字顶部 Y 坐标
     */
    private void drawResourceText(CommonPainter painter, String text, int fontSize, Color color, int width, int y) {
        TextWithStyle style = new TextWithStyle(text, fontSize, color);
        while (fontSize > 12 && painter.getStringWidthAndHeight(style).getLeft() > width - 24) {
            style = new TextWithStyle(text, --fontSize, color);
        }
        drawCenteredText(painter, style, width / 2, y);
    }

    /**
     * 格式化占用率
     * @param value 占用率
     * @return 占用率百分比文字
     */
    private String formatPercentage(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value * 100).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%" : "—";
    }

    /**
     * 格式化 StarBot 占用率
     * @param usage 资源占用率
     * @return StarBot 占用率文字
     */
    private String formatProcessUsage(Usage usage) {
        return "StarBot " + (Double.isFinite(usage.getStarBot()) ? formatPercentage(usage.getStarBot()) : "获取失败");
    }

    /**
     * 格式化已用容量与总容量
     * @param used 已用字节数
     * @param total 总字节数
     * @return 已用容量与总容量说明
     */
    private String formatCapacityDetails(long used, long total) {
        return "已用 " + formatSize(used, " ") + " / 共 " + formatSize(total, " ");
    }

    /**
     * 格式化 StarBot 的资源占比与容量
     * @param usage 资源占用率
     * @param starBotBytes StarBot 占用字节数
     * @return StarBot 资源占比与容量说明
     */
    private String formatCapacityUsage(Usage usage, long starBotBytes) {
        return formatProcessUsage(usage) + (Double.isFinite(usage.getStarBot()) ? " (" + formatSize(starBotBytes, "") + ")" : "");
    }

    /**
     * 格式化容量，自动选择单位
     * @param bytes 字节数
     * @param separator 数值和单位之间的分隔符
     * @return 容量文字
     */
    private String formatSize(long bytes, String separator) {
        if (bytes < 0) {
            return "—";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        long unitSize = 1;
        int unit = 0;
        while (unit < units.length - 1 && bytes / unitSize >= 1024) {
            unitSize *= 1024;
            unit++;
        }
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(unitSize), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + separator + units[unit];
    }

    /**
     * 绘制图例
     * @param painter 绘图器
     * @param centerX 图例中心 X 坐标
     * @param text 图例文字
     * @param color 图例颜色
     */
    private void drawLegend(CommonPainter painter, int centerX, String text, Color color) {
        TextWithStyle label = new TextWithStyle(text, 24, COLOR_DEEP_PURPLE);
        int width = painter.getStringWidthAndHeight(label).getLeft() + 34;
        int x = centerX - width / 2;
        painter.drawRoundedRectangle(x, 449, 22, 22, 22, color);
        painter.drawTextWithStyle(List.of(label), new Point(x + 34, 444));
    }

    /**
     * 绘制底部装饰粉白云朵、星星和圆点及版权信息
     * @param painter 绘图器
     */
    private void drawFooter(CommonPainter painter) {
        CommonPainter copyright = factory.create(WIDTH, 160, true).setPos(0, 18);
        copyright.drawCopyright(MARGIN);
        int height = Math.max(190, copyright.getY() + 65);
        BufferedImage copyrightImage = copyright.createSolidRoundedRectangleBackground(new Color(0, 0, 0, 0), 0).getBufferedImage();

        CommonPainter footer = factory.create(WIDTH, height, false);
        Color cloud = new Color(255, 247, 250);
        Color pinkCloud = new Color(255, 222, 237);
        footer.drawRoundedRectangle(-70, height - 95, 190, 190, 190, cloud);
        footer.drawRoundedRectangle(55, height - 52, 145, 145, 145, cloud);
        footer.drawRoundedRectangle(WIDTH - 70, height - 105, 190, 190, 190, cloud);
        footer.drawRoundedRectangle(WIDTH - 150, height - 58, 135, 135, 135, cloud);
        footer.drawRoundedRectangle(WIDTH - 210, height - 30, 135, 135, 135, pinkCloud);
        footer.drawRoundedRectangle(WIDTH - 125, height - 17, 110, 110, 110, pinkCloud);
        footer.drawRoundedRectangle(128, 35, 23, 23, 23, COLOR_DECORATION_PINK);
        footer.drawRoundedRectangle(WIDTH - 265, height - 37, 19, 19, 19, COLOR_BORDER);
        footer.drawStar(210, 76, 19, Color.WHITE);
        footer.drawStar(75, height - 112, 12, COLOR_STAR);
        footer.drawStar(WIDTH - 110, height - 62, 11, COLOR_STAR);
        footer.drawStar(WIDTH - 190, height - 45, 19, Color.WHITE);
        footer.drawImage(copyrightImage, new Point(0, 0));

        int y = painter.getY() + 12;
        painter.drawImage(footer.getBufferedImage(), new Point(0, y));
        painter.setPos(MARGIN, y + height);
    }
}
