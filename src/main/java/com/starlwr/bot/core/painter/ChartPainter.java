package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.util.ImageUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 统计图绘图器
 */
@Slf4j
public class ChartPainter {
    /**
     * 曲线主色
     */
    public static final Color COLOR_LINE = new Color(238, 73, 121);

    /**
     * 曲线正值填充色
     */
    public static final Color COLOR_FILL_POSITIVE = new Color(251, 114, 153);

    /**
     * 曲线负值填充色
     */
    public static final Color COLOR_FILL_NEGATIVE = new Color(45, 160, 75);

    /**
     * 曲线图平滑采样密度
     */
    private static final int SMOOTH_SAMPLES_PER_SEGMENT = 10;

    /**
     * 曲线图时间刻度格式化器
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 排名条渐变起始色
     */
    public static final Color COLOR_DEEP_BLUE = new Color(55, 187, 248);

    /**
     * 排名条渐变终止色
     */
    public static final Color COLOR_LIGHT_BLUE = new Color(175, 238, 238);

    /**
     * 双向排名正向条渐变起始色
     */
    public static final Color COLOR_DEEP_RED = new Color(240, 128, 128);

    /**
     * 双向排名正向条渐变终止色
     */
    public static final Color COLOR_LIGHT_RED = new Color(255, 220, 220);

    /**
     * 双向排名负向条渐变起始色
     */
    public static final Color COLOR_DEEP_GREEN = new Color(0, 255, 0);

    /**
     * 双向排名负向条渐变终止色
     */
    public static final Color COLOR_LIGHT_GREEN = new Color(184, 255, 184);

    /**
     * 排名图头像尺寸
     */
    private static final int FACE_SIZE = 100;

    /**
     * 排名图头像与条形起始位置偏移
     */
    private static final int BAR_OFFSET = 10;

    /**
     * 排名图条形高度
     */
    private static final int BAR_HEIGHT = 30;

    /**
     * 排名图行间距
     */
    private static final int ROW_SPACE = 25;

    /**
     * 分布图紫色
     */
    public static final Color COLOR_PURPLE = new Color(238, 130, 238);

    /**
     * 分布图调色板
     */
    private static final Color[] PALETTE = {
            COLOR_DEEP_RED, Color.ORANGE, Color.YELLOW, COLOR_DEEP_GREEN, Color.CYAN, COLOR_DEEP_BLUE, COLOR_PURPLE,
            Color.LIGHT_GRAY, COLOR_LIGHT_RED, COLOR_LIGHT_GREEN, COLOR_LIGHT_BLUE
    };

    private ChartPainter() {
    }

    /**
     * 曲线图数据点
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class LinePoint {
        /**
         * 时间戳，单位：毫秒
         */
        private long timestamp;

        /**
         * 数值
         */
        private double value;
    }

    /**
     * 排名图数据项
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class RankingItem {
        /**
         * 头像图片，传入 null 时绘制灰色默认头像
         */
        private BufferedImage face;

        /**
         * 昵称
         */
        private String name;

        /**
         * 数量
         */
        private double value;
    }

    /**
     * 分布图数据项
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class DistributionSlice {
        /**
         * 标签
         */
        private String label;

        /**
         * 数值
         */
        private double value;
    }

    /**
     * 绘制曲线图
     *
     * @param samples     曲线图数据点列表
     * @param cumulative  是否为累计曲线
     * @param bucketCount 分桶数量，传入大于 0 的值时按指定数量分桶聚合后绘制，传入 0 或负数时不分桶，直接使用原始样本绘制，保留完整趋势
     * @param width       图表宽度
     * @param font        文字字体
     * @return 曲线图图片
     */
    public static Optional<BufferedImage> renderLineChart(@NonNull List<LinePoint> samples, boolean cumulative, int bucketCount, int width, @NonNull Font font) {
        return renderLineChart(samples, cumulative, bucketCount, width, font, null, null, null);
    }

    /**
     * 绘制曲线图
     *
     * @param samples            曲线图数据点列表
     * @param cumulative         是否为累计曲线
     * @param bucketCount        分桶数量，传入大于 0 的值时按指定数量分桶聚合后绘制，传入 0 或负数时不分桶，直接使用原始样本绘制，保留完整趋势
     * @param width              图表宽度
     * @param font               文字字体
     * @param lineColor          曲线颜色
     * @param fillPositiveColor  正值填充颜色
     * @param fillNegativeColor  负值填充颜色
     * @return 曲线图图片
     */
    public static Optional<BufferedImage> renderLineChart(@NonNull List<LinePoint> samples, boolean cumulative, int bucketCount, int width, @NonNull Font font, Color lineColor, Color fillPositiveColor, Color fillNegativeColor) {
        if (CollectionUtils.isEmpty(samples)) {
            log.warn("绘制曲线图失败, 数据点列表不能为空");
            return Optional.empty();
        }

        if (width <= 0) {
            log.warn("绘制曲线图失败, 图表宽度必须大于 0");
            return Optional.empty();
        }

        // 根据样本自动计算开始与结束时间戳
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (LinePoint sample : samples) {
            start = Math.min(start, sample.getTimestamp());
            end = Math.max(end, sample.getTimestamp());
        }

        if (end - start < 60_000L) {
            log.warn("绘制曲线图失败, 持续时间不得少于 1 分钟, 当前持续时间: {} 毫秒", end - start);
            return Optional.empty();
        }

        // 分桶数量大于 0 时按指定数量分桶聚合，否则使用原始样本保留完整趋势
        double[] values = null;
        List<Point2D.Double> rawPoints = null;
        if (bucketCount > 0) {
            values = divide(samples, start, end, cumulative, bucketCount);
        } else {
            rawPoints = buildRawPoints(samples, start, end, cumulative);
        }

        // 从平滑前的原始数据计算真实最小最大值
        double minValue = 0;
        double maxValue = 0;
        boolean hasNegative = false;
        if (bucketCount > 0) {
            for (double value : values) {
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
                if (value < 0) {
                    hasNegative = true;
                }
            }
        } else {
            for (Point2D.Double point : rawPoints) {
                minValue = Math.min(minValue, point.getY());
                maxValue = Math.max(maxValue, point.getY());
                if (point.getY() < 0) {
                    hasNegative = true;
                }
            }
        }

        // 纵轴范围取数据真实最小最大值，刻度取整并向两端扩展，确保刻度覆盖数据范围
        double realMin = Math.min(0, minValue);
        double realMax = Math.max(0, maxValue);
        if (realMax <= realMin) {
            realMax = realMin + 1.0;
        }
        double step = niceStep(realMax - realMin, 8);
        double yMin = Math.floor(realMin / step) * step;
        double yMax = Math.ceil(realMax / step) * step;

        Color resolvedLineColor = lineColor != null ? lineColor : COLOR_LINE;
        Color resolvedFillPositiveColor = fillPositiveColor != null ? fillPositiveColor : COLOR_FILL_POSITIVE;
        Color resolvedFillNegativeColor = fillNegativeColor != null ? fillNegativeColor : COLOR_FILL_NEGATIVE;

        int height = width * 500 / 900;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = image.createGraphics();
        quality(draw);
        draw.setColor(Color.WHITE);
        draw.fillRect(0, 0, width, height);

        int left = 72;
        int right = 24;
        int top = 22;
        int bottom = 58;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;
        int zeroY = yToPixel(0, yMin, yMax, top, plotHeight);

        draw.setFont(font);
        draw.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{5f, 5f}, 0f));

        // 绘制横向网格线与数值刻度，刻度步长取整且覆盖数据范围
        double first = Math.ceil(yMin / step) * step;
        int tickCount = (int) ((yMax - first) / step) + 1;
        for (int index = 0; index < tickCount; index++) {
            double value = first + step * index;
            int y = yToPixel(value, yMin, yMax, top, plotHeight);

            draw.setColor(Color.LIGHT_GRAY);
            draw.drawLine(left, y, left + plotWidth, y);

            draw.setColor(Color.DARK_GRAY);
            String label = format(value);
            draw.drawString(label, left - draw.getFontMetrics().stringWidth(label) - 8, y + 5);
        }

        // 绘制纵向时间刻度
        for (int index = 0; index < 5; index++) {
            double ratio = index / 4.0;
            int x = left + (int) (plotWidth * ratio);
            String label = TIME_FORMAT.format(Instant.ofEpochMilli(start + (long) ((end - start) * ratio)).atZone(ZoneId.systemDefault()));

            draw.setColor(Color.DARK_GRAY);
            int labelX = Math.max(0, Math.min(width - draw.getFontMetrics().stringWidth(label),
                    x - draw.getFontMetrics().stringWidth(label) / 2));
            draw.drawString(label, labelX, height - 20);
        }

        // 绘制坐标轴
        draw.setStroke(new BasicStroke(2f));
        draw.setColor(Color.DARK_GRAY);
        draw.drawLine(left, zeroY, left + plotWidth + 5, zeroY);
        draw.drawLine(left, top + plotHeight, left, top - 5);
        draw.drawLine(left + plotWidth + 5, zeroY, left + plotWidth - 4, zeroY - 5);
        draw.drawLine(left + plotWidth + 5, zeroY, left + plotWidth - 4, zeroY + 5);
        draw.drawLine(left, top - 5, left - 5, top + 4);
        draw.drawLine(left, top - 5, left + 5, top + 4);

        // 平滑处理曲线数据
        List<Point2D.Double> points;
        if (bucketCount > 0) {
            points = smooth(values, SMOOTH_SAMPLES_PER_SEGMENT);
        } else {
            points = smoothPoints(rawPoints, SMOOTH_SAMPLES_PER_SEGMENT);
        }

        // 线条平滑导致峰值虚高、谷值虚低时，将曲线封顶至真实最小最大值
        for (Point2D.Double point : points) {
            double y = Math.max(realMin, Math.min(point.getY(), realMax));
            point.setLocation(point.getX(), y);
        }

        // 累计计数曲线应为单调不减曲线，线条平滑可能会导致回落，此处额外强制单调不减
        if (cumulative && !hasNegative) {
            double previous = realMin;
            for (Point2D.Double point : points) {
                double y = Math.max(point.getY(), previous);
                point.setLocation(point.getX(), y);
                previous = y;
            }
        }

        Composite oldComposite = draw.getComposite();
        draw.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
        for (int i = 0; i < points.size() - 1; i++) {
            Point2D.Double a = points.get(i);
            Point2D.Double b = points.get(i + 1);

            int x1 = left + (int) (a.getX() * plotWidth);
            int x2 = left + (int) (b.getX() * plotWidth);
            int y1 = yToPixel(a.getY(), yMin, yMax, top, plotHeight);
            int y2 = yToPixel(b.getY(), yMin, yMax, top, plotHeight);

            double ay = a.getY();
            double by = b.getY();
            if ((ay < 0 && by > 0) || (ay > 0 && by < 0)) {
                // 线段跨越零轴，在零点处分割为两段分别上色，避免单一颜色覆盖错误
                double t = ay / (ay - by);
                int x0 = left + (int) ((a.getX() + (b.getX() - a.getX()) * t) * plotWidth);

                draw.setColor(ay >= 0 ? resolvedFillPositiveColor : resolvedFillNegativeColor);
                Path2D.Double area1 = new Path2D.Double();
                area1.moveTo(x1, zeroY);
                area1.lineTo(x1, y1);
                area1.lineTo(x0, zeroY);
                area1.closePath();
                draw.fill(area1);

                draw.setColor(by >= 0 ? resolvedFillPositiveColor : resolvedFillNegativeColor);
                Path2D.Double area2 = new Path2D.Double();
                area2.moveTo(x0, zeroY);
                area2.lineTo(x2, y2);
                area2.lineTo(x2, zeroY);
                area2.closePath();
                draw.fill(area2);
            } else {
                draw.setColor((ay + by) / 2 >= 0 ? resolvedFillPositiveColor : resolvedFillNegativeColor);
                Path2D.Double area = new Path2D.Double();
                area.moveTo(x1, zeroY);
                area.lineTo(x1, y1);
                area.lineTo(x2, y2);
                area.lineTo(x2, zeroY);
                area.closePath();
                draw.fill(area);
            }
        }
        draw.setComposite(oldComposite);

        draw.setColor(resolvedLineColor);
        draw.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double line = new Path2D.Double();
        for (int index = 0; index < points.size(); index++) {
            Point2D.Double point = points.get(index);
            double x = left + point.getX() * plotWidth;
            double y = yToPixel(point.getY(), yMin, yMax, top, plotHeight);
            if (index == 0) {
                line.moveTo(x, y);
            } else {
                line.lineTo(x, y);
            }
        }
        draw.draw(line);

        draw.dispose();
        return Optional.of(image);
    }

    /**
     * 绘制排名图
     *
     * @param items 排名数据项列表，需按数量降序排序
     * @param width 图表宽度
     * @param font  文字字体
     * @return 排名图图片
     */
    public static Optional<BufferedImage> renderRankingChart(@NonNull List<RankingItem> items, int width, @NonNull Font font) {
        return renderRankingChart(items, width, font, null, null, null, null);
    }

    /**
     * 绘制排名图
     *
     * @param items               排名数据项列表，需按数量降序排序
     * @param width               图表宽度
     * @param font                文字字体
     * @param barStartColor       正向条形渐变起始颜色
     * @param barEndColor         正向条形渐变终止颜色
     * @param negativeStartColor  反向条形渐变起始颜色
     * @param negativeEndColor    反向条形渐变终止颜色
     * @return 排名图图片
     */
    public static Optional<BufferedImage> renderRankingChart(@NonNull List<RankingItem> items, int width, @NonNull Font font, Color barStartColor, Color barEndColor, Color negativeStartColor, Color negativeEndColor) {
        if (CollectionUtils.isEmpty(items)) {
            log.warn("绘制排名图失败, 数据列表不能为空");
            return Optional.empty();
        }

        if (width <= 0) {
            log.warn("绘制排名图失败, 图表宽度必须大于 0");
            return Optional.empty();
        }

        boolean hasPositive = false;
        boolean hasNegative = false;
        for (RankingItem item : items) {
            if (item.getValue() > 0) {
                hasPositive = true;
            } else if (item.getValue() < 0) {
                hasNegative = true;
            }
        }

        if (hasPositive && hasNegative) {
            return Optional.of(renderDoubleRanking(items, width, font, barStartColor, barEndColor, negativeStartColor, negativeEndColor));
        }
        return Optional.of(renderSingleRanking(items, width, font, barStartColor, barEndColor));
    }

    /**
     * 绘制单向排名图，条形向右延伸，头像位于左侧
     *
     * @param items         排名数据项列表，需按数量降序排序
     * @param width         图表宽度
     * @param font          文字字体
     * @param barStartColor 条形渐变起始颜色
     * @param barEndColor   条形渐变终止颜色
     * @return 单向排名图图片
     */
    private static BufferedImage renderSingleRanking(List<RankingItem> items, int width, Font font, Color barStartColor, Color barEndColor) {
        double top = Math.max(items.get(0).getValue(), Math.abs(items.get(items.size() - 1).getValue()));
        Color startColor = barStartColor != null ? barStartColor : COLOR_DEEP_BLUE;
        Color endColor = barEndColor != null ? barEndColor : COLOR_LIGHT_BLUE;

        int count = items.size();
        int height = FACE_SIZE * count + ROW_SPACE * (count - 1);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = image.createGraphics();
        quality(draw);
        draw.setFont(font);

        int barX = FACE_SIZE - BAR_OFFSET;
        int topBarWidth = width - FACE_SIZE + BAR_OFFSET;
        int textBaselineOffset = draw.getFontMetrics().getAscent();
        int barYOffset = Math.max((FACE_SIZE - BAR_HEIGHT) / 2, draw.getFontMetrics().getHeight() + 4);

        for (int i = 0; i < count; i++) {
            RankingItem item = items.get(i);
            int y = i * (FACE_SIZE + ROW_SPACE);
            int barWidth = top != 0 ? (int) (Math.abs(item.getValue()) / top * topBarWidth) : 0;

            if (barWidth != 0) {
                BufferedImage bar = createGradientBar(barWidth, BAR_HEIGHT, startColor, endColor, false);
                draw.drawImage(bar, barX, y + barYOffset, null);
            }

            String countText = format(item.getValue());
            int nameX = barX + BAR_OFFSET * 2;
            int baseline = y + textBaselineOffset;

            if (StringUtil.isNotBlank(item.getName())) {
                draw.setColor(Color.BLACK);
                draw.drawString(item.getName(), nameX, baseline);
            }

            int nameWidth = StringUtil.isNotBlank(item.getName()) ? draw.getFontMetrics().stringWidth(item.getName()) : 0;
            int countX = Math.max(barWidth, barX + BAR_OFFSET * 3 + nameWidth);
            draw.setColor(Color.GRAY);
            draw.drawString(countText, countX, baseline);

            drawAvatar(draw, item.getFace(), 0, y);
        }

        draw.dispose();
        return image;
    }

    /**
     * 绘制双向排名图，数量为正的条形向右延伸，数量为负的条形向左延伸，头像位于中间
     *
     * @param items               排名数据项列表，需按数量降序排序
     * @param width               图表宽度
     * @param font                文字字体
     * @param positiveStart       正向条形渐变起始颜色
     * @param positiveEnd         正向条形渐变终止颜色
     * @param negativeStart       反向条形渐变起始颜色
     * @param negativeEnd         反向条形渐变终止颜色
     * @return 双向排名图图片
     */
    private static BufferedImage renderDoubleRanking(List<RankingItem> items, int width, Font font, Color positiveStart, Color positiveEnd, Color negativeStart, Color negativeEnd) {
        double top = items.stream().mapToDouble(item -> Math.abs(item.getValue())).max().orElse(0);
        Color posStartColor = positiveStart != null ? positiveStart : COLOR_DEEP_RED;
        Color posEndColor = positiveEnd != null ? positiveEnd : COLOR_LIGHT_RED;
        Color negStartColor = negativeStart != null ? negativeStart : COLOR_LIGHT_GREEN;
        Color negEndColor = negativeEnd != null ? negativeEnd : COLOR_DEEP_GREEN;

        int count = items.size();
        int height = FACE_SIZE * count + ROW_SPACE * (count - 1);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = image.createGraphics();
        quality(draw);
        draw.setFont(font);

        int faceX = (width - FACE_SIZE) / 2;
        int barX = faceX + FACE_SIZE - BAR_OFFSET;
        int reverseBarX = faceX + BAR_OFFSET;
        int topBarWidth = (width - FACE_SIZE) / 2 + BAR_OFFSET;
        int textBaselineOffset = draw.getFontMetrics().getAscent();
        int barYOffset = Math.max((FACE_SIZE - BAR_HEIGHT) / 2, draw.getFontMetrics().getHeight() + 4);

        for (int i = 0; i < count; i++) {
            RankingItem item = items.get(i);
            double value = item.getValue();
            int y = i * (FACE_SIZE + ROW_SPACE);
            int barWidth = top != 0 ? (int) (Math.abs(value) / top * topBarWidth) : 0;

            if (barWidth != 0) {
                if (value > 0) {
                    BufferedImage bar = createGradientBar(barWidth, BAR_HEIGHT, posStartColor, posEndColor, false);
                    draw.drawImage(bar, barX, y + barYOffset, null);
                } else if (value < 0) {
                    BufferedImage bar = createGradientBar(barWidth, BAR_HEIGHT, negStartColor, negEndColor, true);
                    draw.drawImage(bar, reverseBarX - barWidth, y + barYOffset, null);
                }
            }

            String countText = format(value);
            int baseline = y + textBaselineOffset;
            int nameWidth = StringUtil.isNotBlank(item.getName()) ? draw.getFontMetrics().stringWidth(item.getName()) : 0;

            if (value >= 0) {
                int nameX = barX + BAR_OFFSET * 2;
                if (StringUtil.isNotBlank(item.getName())) {
                    draw.setColor(Color.BLACK);
                    draw.drawString(item.getName(), nameX, baseline);
                }

                int countX = Math.max(faceX + barWidth, barX + BAR_OFFSET * 3 + nameWidth);
                draw.setColor(Color.GRAY);
                draw.drawString(countText, countX, baseline);
            } else {
                int countWidth = draw.getFontMetrics().stringWidth(countText);

                int nameX = reverseBarX - BAR_OFFSET * 2 - nameWidth;
                if (StringUtil.isNotBlank(item.getName())) {
                    draw.setColor(Color.BLACK);
                    draw.drawString(item.getName(), nameX, baseline);
                }

                int countX = Math.min(faceX + FACE_SIZE - barWidth - countWidth,
                        reverseBarX - BAR_OFFSET * 3 - nameWidth - countWidth);
                draw.setColor(Color.GRAY);
                draw.drawString(countText, countX, baseline);
            }

            drawAvatar(draw, item.getFace(), faceX, y);
        }

        draw.dispose();
        return image;
    }

    /**
     * 绘制分布图
     *
     * @param slices 分布数据项列表
     * @param width  图表宽度
     * @param font   文字字体
     * @return 分布图图片
     */
    public static Optional<BufferedImage> renderDistributionChart(@NonNull List<DistributionSlice> slices, int width, @NonNull Font font) {
        if (slices.isEmpty()) {
            log.warn("绘制分布图失败, 数据列表不能为空");
            return Optional.empty();
        }

        if (width <= 0) {
            log.warn("绘制分布图失败, 图表宽度必须大于 0");
            return Optional.empty();
        }

        double total = 0;
        for (DistributionSlice slice : slices) {
            total += Math.max(0, slice.getValue());
        }

        if (total <= 0) {
            log.warn("绘制分布图失败, 数值总和必须大于 0");
            return Optional.empty();
        }

        int count = slices.size();
        int margin = 56;
        int topSpace = 28;
        int barHeight = 28;
        int gap = 34;
        int bottomSpace = 28;
        int itemGap = 24;
        int legendRight = width - margin;
        int maxRowWidth = legendRight - margin;

        // 模拟图例布局，按固定项间距分组换行，行内绘制时再两端对齐
        BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metricsDraw = metricsImage.createGraphics();
        FontMetrics metrics = metricsDraw.getFontMetrics(font);
        metricsDraw.dispose();

        int rowHeight = Math.max(34, metrics.getHeight() + 5);
        // 预计算每项右侧的数值与占比文本
        DecimalFormat percentFormat = new DecimalFormat("0.#");
        String[] rightTexts = new String[count];
        int[] rightWidths = new int[count];
        for (int i = 0; i < count; i++) {
            double value = Math.max(0, slices.get(i).getValue());
            rightTexts[i] = format(value) + " (" + percentFormat.format(value / total * 100) + "%)";
            rightWidths[i] = metrics.stringWidth(rightTexts[i]);
        }
        List<List<Integer>> rowGroups = new ArrayList<>();
        List<Integer> currentRow = new ArrayList<>();
        int lineX = margin;
        for (int i = 0; i < count; i++) {
            int labelLimit = Math.max(maxRowWidth - 28 - rightWidths[i], 0);
            String rawLabel = slices.get(i).getLabel();
            int rawLabelWidth = StringUtil.isNotBlank(rawLabel) ? metrics.stringWidth(rawLabel) : 0;
            // 计算完整标签宽度（不省略）与省略后宽度（独占一行仍放不下时使用）
            int fullItemWidth = 20 + rawLabelWidth + 8 + rightWidths[i];
            int fittedItemWidth = 20 + Math.min(rawLabelWidth, labelLimit) + 8 + rightWidths[i];
            if (!currentRow.isEmpty() && lineX + fullItemWidth > legendRight) {
                // 当前行放不下完整标签，换行
                rowGroups.add(currentRow);
                currentRow = new ArrayList<>();
                lineX = margin;
            }
            if (lineX + fullItemWidth > legendRight) {
                // 标签独占一行仍放不下，以省略宽度放入该行
                currentRow.add(i);
                lineX += fittedItemWidth + itemGap;
            } else {
                // 完整标签放得下，按完整宽度放入
                currentRow.add(i);
                lineX += fullItemWidth + itemGap;
            }
        }
        if (!currentRow.isEmpty()) {
            rowGroups.add(currentRow);
        }
        int legendTop = topSpace + barHeight + gap;
        int height = legendTop + (rowGroups.size() - 1) * rowHeight + bottomSpace;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = image.createGraphics();
        quality(draw);
        draw.setColor(Color.WHITE);
        draw.fillRect(0, 0, width, height);

        // 绘制圆角堆叠条
        int barWidth = Math.max(width - margin * 2, 10);
        BufferedImage barLayer = new BufferedImage(barWidth, barHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D barDraw = barLayer.createGraphics();
        quality(barDraw);

        // 计算各段可见宽度，占比极小的段至少保留 3 像素
        int[] segmentWidths = new int[count];
        int sum = 0;
        for (int i = 0; i < count; i++) {
            double value = Math.max(0, slices.get(i).getValue());
            segmentWidths[i] = Math.max((int) Math.round(value / total * barWidth), 3);
            sum += segmentWidths[i];
        }
        if (sum > barWidth) {
            // 由于极小段保底 3 像素导致合计超宽，从末段起依次压缩至最小 3 像素
            for (int i = count - 1; i >= 0 && sum > barWidth; i--) {
                int shrink = Math.min(sum - barWidth, segmentWidths[i] - 3);
                segmentWidths[i] -= shrink;
                sum -= shrink;
            }
        } else if (sum < barWidth) {
            // 由于取整误差导致合计不足，末段补足
            segmentWidths[count - 1] += barWidth - sum;
        }

        // 绘制各段
        int x = 0;
        for (int i = 0; i < count; i++) {
            if (segmentWidths[i] <= 0) {
                continue;
            }
            barDraw.setColor(PALETTE[i % PALETTE.length]);
            barDraw.fillRect(x, 0, segmentWidths[i], barHeight);
            x += segmentWidths[i];
        }
        barDraw.dispose();
        draw.drawImage(ImageUtil.maskToRoundedRectangle(barLayer, barHeight / 2), margin, topSpace, null);

        // 绘制图例，自动换行，行内两端对齐
        draw.setFont(font);
        int legendY = legendTop;
        for (List<Integer> row : rowGroups) {
            // 统计本行各项宽度，将剩余空间均匀分配到项间间距实现两端对齐
            int sumWidth = 0;
            for (int idx : row) {
                int labelLimit = Math.max(maxRowWidth - 28 - rightWidths[idx], 0);
                int labelWidth = legendLabelWidth(metrics, slices.get(idx).getLabel(), labelLimit);
                sumWidth += 20 + labelWidth + 8 + rightWidths[idx];
            }
            int extra = row.size() > 1 ? (maxRowWidth - sumWidth) / (row.size() - 1) : 0;
            int itemX = margin;
            for (int idx : row) {
                DistributionSlice slice = slices.get(idx);
                String label = slice.getLabel();
                int labelLimit = Math.max(maxRowWidth - 28 - rightWidths[idx], 0);
                if (StringUtil.isNotBlank(label) && metrics.stringWidth(label) > labelLimit) {
                    label = limitLabel(draw, label, itemX + 20, itemX + 20 + labelLimit);
                }
                int labelWidth = legendLabelWidth(metrics, label, labelLimit);
                int itemWidth = 20 + labelWidth + 8 + rightWidths[idx];

                draw.setColor(PALETTE[idx % PALETTE.length]);
                draw.fillOval(itemX, legendY - (int) (draw.getFontMetrics().getAscent() * 0.45) - 6, 12, 12);
                if (StringUtil.isNotBlank(label)) {
                    draw.setColor(Color.BLACK);
                    draw.drawString(label, itemX + 20, legendY);
                }
                draw.setColor(Color.GRAY);
                draw.drawString(rightTexts[idx], itemX + 20 + labelWidth + 8, legendY);

                itemX += itemWidth + extra;
            }
            legendY += rowHeight;
        }

        draw.dispose();
        return Optional.of(image);
    }

    /**
     * 格式化数值，小数保留两位以内
     *
     * @param value 数值
     * @return 格式化后的字符串
     */
    private static String format(double value) {
        if (Math.abs(value - (long) value) < 0.001) {
            return String.valueOf((long) value);
        }
        return new DecimalFormat("0.##").format(value);
    }

    /**
     * 设置绘图质量
     *
     * @param draw Graphics2D 实例
     */
    private static void quality(Graphics2D draw) {
        draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        draw.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /**
     * 将曲线图数据点列表分桶聚合
     *
     * @param samples     曲线图数据点列表
     * @param start       开始时间戳
     * @param end         结束时间戳
     * @param cumulative  是否为累计曲线
     * @param bucketCount 分桶数量
     * @return 分桶聚合结果
     */
    private static double[] divide(List<LinePoint> samples, long start, long end, boolean cumulative, int bucketCount) {
        double[] result = new double[bucketCount];
        long duration = end - start;

        for (LinePoint sample : samples) {
            long timestamp = sample.getTimestamp();
            if (timestamp >= start && timestamp <= end) {
                int index = (int) Math.min(Math.max((timestamp - start) * bucketCount / duration, 0), bucketCount - 1);
                result[index] += sample.getValue();
            }
        }

        // 累计模式转换为前缀和，末位元素即为全部样本的累计总和；非累计模式保持各桶实际数值
        if (cumulative) {
            double[] cumulativeValues = new double[bucketCount + 1];
            double total = 0;
            for (int index = 0; index < bucketCount; index++) {
                total += result[index];
                cumulativeValues[index + 1] = total;
            }
            result = cumulativeValues;
        } else {
            double[] interactionValues = new double[bucketCount + 1];
            interactionValues[0] = 0;
            System.arraycopy(result, 0, interactionValues, 1, bucketCount);
            result = interactionValues;
        }

        return result;
    }

    /**
     * 使用原始样本构建曲线数据点，不做分桶聚合，保留完整趋势
     *
     * @param samples    曲线图数据点列表
     * @param start      开始时间戳
     * @param end        结束时间戳
     * @param cumulative 是否为累计曲线，为 true 时按时间排序后计算前缀和
     * @return 曲线数据点列表
     */
    private static List<Point2D.Double> buildRawPoints(List<LinePoint> samples, long start, long end, boolean cumulative) {
        List<LinePoint> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingLong(LinePoint::getTimestamp));

        List<Point2D.Double> points = new ArrayList<>();
        double duration = end - start;
        double running = 0;

        if (cumulative) {
            points.add(new Point2D.Double(0.0, 0.0));
        }

        for (LinePoint sample : sorted) {
            double value = sample.getValue();
            if (cumulative) {
                running += value;
                value = running;
            }
            double x = Math.max(0, Math.min(1, (double) (sample.getTimestamp() - start) / duration));
            points.add(new Point2D.Double(x, value));
        }

        return points;
    }

    /**
     * Catmull-Rom 平滑处理折线图数据
     *
     * @param values            原始数据
     * @param samplesPerSegment 每段采样数量
     * @return 平滑处理后的数据点列表
     */
    private static List<Point2D.Double> smooth(double[] values, int samplesPerSegment) {
        List<Point2D.Double> output = new ArrayList<>((values.length - 1) * samplesPerSegment + 1);

        if (values.length < 3) {
            for (int i = 0; i < values.length; i++) {
                output.add(new Point2D.Double(i, values[i]));
            }
            return output;
        }

        for (int index = 0; index < values.length - 1; index++) {
            double p0 = values[Math.max(index - 1, 0)];
            double p1 = values[index];
            double p2 = values[index + 1];
            double p3 = values[Math.min(index + 2, values.length - 1)];
            double minY = Math.min(p1, p2);
            double maxY = Math.max(p1, p2);

            for (int part = 0; part < samplesPerSegment; part++) {
                double t = (double) part / samplesPerSegment;
                double value = Math.max(minY, Math.min(centripetalY(p0, p1, p2, p3, t), maxY));
                output.add(new Point2D.Double((index + t) / (values.length - 1), value));
            }
        }

        output.add(new Point2D.Double(1.0, values[values.length - 1]));
        return output;
    }

    /**
     * Catmull-Rom 平滑处理非等距数据点
     *
     * @param points            原始数据点列表
     * @param samplesPerSegment 每段采样数量
     * @return 平滑处理后的数据点列表
     */
    private static List<Point2D.Double> smoothPoints(List<Point2D.Double> points, int samplesPerSegment) {
        int length = points.size();
        if (length < 3) {
            return new ArrayList<>(points);
        }

        List<Point2D.Double> output = new ArrayList<>((length - 1) * samplesPerSegment + 1);
        for (int index = 0; index < length - 1; index++) {
            Point2D.Double p0 = points.get(Math.max(index - 1, 0));
            Point2D.Double p1 = points.get(index);
            Point2D.Double p2 = points.get(index + 1);
            Point2D.Double p3 = points.get(Math.min(index + 2, length - 1));
            double minY = Math.min(p1.getY(), p2.getY());
            double maxY = Math.max(p1.getY(), p2.getY());

            for (int part = 0; part < samplesPerSegment; part++) {
                double t = (double) part / samplesPerSegment;

                double x = p1.getX() + (p2.getX() - p1.getX()) * t;
                double y = Math.max(minY, Math.min(centripetalY(p0.getY(), p1.getY(), p2.getY(), p3.getY(), t), maxY));
                output.add(new Point2D.Double(x, y));
            }
        }

        output.add(points.get(length - 1));
        return output;
    }

    /**
     * Centripetal Catmull-Rom 插值，相比均匀参数化版本过冲更小，避免峰值周边失真
     *
     * @param p0 前一控制点
     * @param p1 当前段起点控制点
     * @param p2 当前段终点控制点
     * @param p3 后一控制点
     * @param t  段内插值比例，范围：0 ~ 1
     * @return 插值结果
     */
    private static double centripetalY(double p0, double p1, double p2, double p3, double t) {
        double epsilon = 1e-6;
        // 弦长方根参数化，避免均匀参数化在数值突变处的过冲
        double t0 = 0;
        double t1 = t0 + Math.sqrt(Math.abs(p1 - p0) + epsilon);
        double t2 = t1 + Math.sqrt(Math.abs(p2 - p1) + epsilon);
        double t3 = t2 + Math.sqrt(Math.abs(p3 - p2) + epsilon);
        double u = t1 + (t2 - t1) * t;

        // Barry-Goldman 形心公式
        double a1 = (t1 - u) / (t1 - t0) * p0 + (u - t0) / (t1 - t0) * p1;
        double a2 = (t2 - u) / (t2 - t1) * p1 + (u - t1) / (t2 - t1) * p2;
        double a3 = (t3 - u) / (t3 - t2) * p2 + (u - t2) / (t3 - t2) * p3;
        double b1 = (t2 - u) / (t2 - t0) * a1 + (u - t0) / (t2 - t0) * a2;
        double b2 = (t3 - u) / (t3 - t1) * a2 + (u - t1) / (t3 - t1) * a3;
        return (t2 - u) / (t2 - t1) * b1 + (u - t1) / (t2 - t1) * b2;
    }

    /**
     * 将数值转换为像素坐标
     *
     * @param value  数值
     * @param min    数值最小值
     * @param max    数值最大值
     * @param top    绘图区域顶部坐标
     * @param height 绘图区域高度
     * @return 像素坐标
     */
    private static int yToPixel(double value, double min, double max, int top, int height) {
        double range = max - min;
        if (range <= 0) {
            range = 1.0;
        }
        return (int) Math.max(top, Math.min(top + height, top + (max - value) / range * height));
    }

    /**
     * 计算美观刻度步长，从 1、2、5 与 10 的幂次组合中选取，保证刻度为整数值
     *
     * @param range       数值范围
     * @param targetCount 目标刻度数量
     * @return 美观刻度步长
     */
    private static double niceStep(double range, int targetCount) {
        double rawStep = range / targetCount;
        double magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double normalized = rawStep / magnitude;

        double nice;
        if (normalized < 1.5) {
            nice = 1;
        } else if (normalized < 3) {
            nice = 2;
        } else if (normalized < 7) {
            nice = 5;
        } else {
            nice = 10;
        }
        return nice * magnitude;
    }

    /**
     * 生成渐变条形图片
     *
     * @param width      条形宽度
     * @param height     条形高度
     * @param startColor 渐变起始颜色
     * @param endColor   渐变终止颜色
     * @param reverse    是否为反向条形，反向条形切左下角，正向条形切右上角
     * @return 渐变条形图片
     */
    private static BufferedImage createGradientBar(int width, int height, Color startColor, Color endColor, boolean reverse) {
        width = Math.max(width, 1);
        height = Math.max(height, 1);

        BufferedImage bar = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = bar.createGraphics();
        quality(draw);

        LinearGradientPaint gradient = new LinearGradientPaint(0, 0, width, 0, new float[]{0f, 1f}, new Color[]{startColor, endColor});
        draw.setPaint(gradient);

        Path2D.Double shape = new Path2D.Double();
        if (reverse) {
            shape.moveTo(0, 0);
            shape.lineTo(width, 0);
            shape.lineTo(width, height);
            shape.lineTo(Math.min(height, width), height);
            shape.closePath();
        } else {
            shape.moveTo(0, 0);
            shape.lineTo(Math.max(width - height, 0), 0);
            shape.lineTo(width, height);
            shape.lineTo(0, height);
            shape.closePath();
        }
        draw.fill(shape);

        draw.dispose();
        return bar;
    }

    /**
     * 绘制排名头像，头像为 null 时绘制灰色圆占位
     *
     * @param draw Graphics2D 实例
     * @param face 头像图片，可为 null
     * @param x    绘制 x 坐标
     * @param y    绘制 y 坐标
     */
    private static void drawAvatar(Graphics2D draw, BufferedImage face, int x, int y) {
        if (face == null) {
            draw.setColor(Color.LIGHT_GRAY);
            draw.fillOval(x, y, FACE_SIZE, FACE_SIZE);
        } else {
            draw.drawImage(ImageUtil.maskToCircle(ImageUtil.resize(face, FACE_SIZE, FACE_SIZE)), x, y, null);
        }
    }

    /**
     * 计算图例标签的显示宽度，标签为空白时宽度为 0
     *
     * @param metrics    FontMetrics 实例
     * @param label      标签文本
     * @param labelLimit 标签最大宽度
     * @return 标签显示宽度
     */
    private static int legendLabelWidth(FontMetrics metrics, String label, int labelLimit) {
        if (!StringUtil.isNotBlank(label)) {
            return 0;
        }
        return Math.min(metrics.stringWidth(label), labelLimit);
    }

    /**
     * 截断文本至指定宽度，超出部分以省略号结尾
     *
     * @param draw   Graphics2D 实例
     * @param text   文本
     * @param startX 文本起始 x 坐标
     * @param endX   文本终止 x 坐标
     * @return 截断后的文本
     */
    private static String limitLabel(Graphics2D draw, String text, int startX, int endX) {
        int maxWidth = endX - startX;
        if (draw.getFontMetrics().stringWidth(text) <= maxWidth) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = result + text.substring(i, i + 1) + "…";
            if (draw.getFontMetrics().stringWidth(candidate) > maxWidth) {
                break;
            }
            result.append(text.charAt(i));
        }
        return result + "…";
    }
}
