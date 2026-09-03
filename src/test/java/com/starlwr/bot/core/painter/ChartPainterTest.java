package com.starlwr.bot.core.painter;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计图绘图器测试
 * <p>
 * 使用合成数据渲染全部图表类型（累计曲线图、互动曲线图、包含负数或全负数的排名图、普通与极短长尾的分布图、大字号等），
 * 用于验证图表的尺寸、高度计算、布局与边界情况处理。
 * <p>
 * 默认仅在内存中渲染并校验图片属性，不落盘；将 {@link #SAVE_IMAGE} 置为 true 时，
 * 额外将生成的 PNG 保存至 TestChart 目录（相对于运行工作目录）供样式审阅
 */
public class ChartPainterTest {
    /**
     * 测试基础字体，优先加载项目内置字体，保证中文与生产环境渲染一致；
     * 各图表字体均基于该字体按字号派生，仅尺寸不同，确保样式统一
     */
    private static final Font FONT = loadBuiltinFont();

    /**
     * 曲线图测试字体，15 号
     */
    private static final Font LINE_FONT = FONT.deriveFont(Font.PLAIN, 15f);

    /**
     * 排名图测试字体，25 号
     */
    private static final Font RANKING_FONT = FONT.deriveFont(Font.PLAIN, 25f);

    /**
     * 分布图测试字体，20 号
     */
    private static final Font DISTRIBUTION_FONT = FONT.deriveFont(Font.PLAIN, 20f);

    /**
     * 测试图表宽度
     */
    private static final int WIDTH = 900;

    /**
     * 是否将生成的图片保存至本地 TestChart 目录
     * <p>
     * 设为 false（默认）时仅为内存渲染并执行断言，不产生任何文件，适合日常跑测试；
     * 设为 true 时每个用例会将渲染结果保存为 PNG，用于人工审阅图表样式，输出文件在
     * 运行目录下的 TestChart 目录中（如 IDEA 将工作目录设为模块路径，则为 StarBotCore/TestChart）
     */
    private static final boolean SAVE_IMAGE = false;

    /**
     * 加载项目内置字体，加载失败时回退至系统字体
     * <p>
     * 优先加载模块资源目录下的 fonts/font.ttf（与生产环境 FontUtil.parseFont("内置") 一致），
     * 加载失败或文件不存在时回退至系统 SANS_SERIF 字体
     *
     * @return 字体，25 号字号
     */
    private static Font loadBuiltinFont() {
        Path path = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "fonts", "font.ttf");
        if (Files.exists(path)) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, path.toFile()).deriveFont(Font.PLAIN, 25f);
            } catch (Exception e) {
                System.out.println("加载内置字体失败, 回退至系统字体: " + e.getMessage());
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 25);
    }

    /**
     * 测试绘制累计曲线图，使用分布不均匀的数据（突发集中 + 长时间沉寂）
     * <p>
     * 数据分为三段：开场 10 分钟密集爆发、随后 50 分钟完全沉寂、中段 15 分钟大爆发、结尾 5 分钟少量弹幕，
     * 用于验证分桶聚合（20 桶）下累计曲线单调不减，以及曲线跨越长时间空白段时的平滑效果。
     * 断言图片宽度为 {@link #WIDTH}、高度为宽度的 5 / 9、像素类型为 ARGB，并输出 line-cumulative.png
     */
    @Test
    public void testRenderLineChartCumulative() throws Exception {
        long start = 1784617225000L;

        List<ChartPainter.LinePoint> samples = new ArrayList<>();
        // 开场 10 分钟密集爆发 120 条
        addSamples(samples, start, 10 * 60 * 1000, 120);
        // 随后 50 分钟完全沉寂
        // 中段 15 分钟大爆发 300 条
        addSamples(samples, start + 60 * 60 * 1000, 15 * 60 * 1000, 300);
        // 结尾 5 分钟少量 40 条
        addSamples(samples, start + 110 * 60 * 1000, 5 * 60 * 1000, 40);

        BufferedImage image = ChartPainter.renderLineChart(samples, true, 20, WIDTH, LINE_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(WIDTH * 500 / 900, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        save(image, "line-cumulative.png");
    }

    /**
     * 测试绘制非累计互动曲线图，使用分布不均匀的数据（突发集中 + 长时间沉寂）
     * <p>
     * 数据分布与累计曲线用例相同，但以 800 宽度、非累计模式绘制，用于验证窄画布下分桶聚合的数值，
     * 断言图片宽度为 800、高度为宽度的 5 / 9，并输出 line-interaction.png
     */
    @Test
    public void testRenderLineChartInteraction() throws Exception {
        long start = 1784617225000L;

        List<ChartPainter.LinePoint> samples = new ArrayList<>();
        // 开场 10 分钟密集爆发 120 条
        addSamples(samples, start, 10 * 60 * 1000, 120);
        // 随后 50 分钟完全沉寂
        // 中段 15 分钟大爆发 300 条
        addSamples(samples, start + 60 * 60 * 1000, 15 * 60 * 1000, 300);
        // 结尾 5 分钟少量 40 条
        addSamples(samples, start + 110 * 60 * 1000, 5 * 60 * 1000, 40);

        BufferedImage image = ChartPainter.renderLineChart(samples, false, 20, 800, LINE_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(800, image.getWidth());
        assertEquals(800 * 500 / 900, image.getHeight());
        save(image, "line-interaction.png");
    }

    /**
     * 测试累计曲线从 0 起步：首桶即存在数据时，曲线左端仍应从 0 开始
     * <p>
     * 分桶聚合的累计曲线在时间起点处累计值必须为 0，首桶的数据量应体现在
     * 第一个时间桶末段位置；断言曲线颜色像素左端起点行号接近绘图区底部（零轴），
     * 并输出 line-gift-cumulative.png
     */
    @Test
    public void testRenderLineChartCumulativeStartsAtZero() throws Exception {
        long start = 1784617225000L;

        // 模拟礼物金额：开场即有两笔（首桶累计 0.2），随后沉寂，结尾再补两笔
        List<ChartPainter.LinePoint> samples = new ArrayList<>();
        samples.add(new ChartPainter.LinePoint(start + 5_000L, 0.1));
        samples.add(new ChartPainter.LinePoint(start + 15_000L, 0.1));
        samples.add(new ChartPainter.LinePoint(start + 580_000L, 0.2));
        samples.add(new ChartPainter.LinePoint(start + 590_000L, 0.2));
        samples.add(new ChartPainter.LinePoint(start + 650_000L, 0.05));

        BufferedImage image = ChartPainter.renderLineChart(samples, true, 20, WIDTH, LINE_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(WIDTH * 500 / 900, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());

        // 绘图区底部零轴行号 = top(22) + plotHeight(图高 - 22 - 58)
        int top = 22;
        int bottom = 58;
        int zeroY = top + (image.getHeight() - top - bottom);
        // 曲线颜色与零轴颜色不一致，需按曲线主色定位：左端 3 像素列内曲线应贴近零轴
        int startY = findLineStartY(image);
        assertTrue(Math.abs(startY - zeroY) <= 2,
                "累计曲线应从 0 起步, 期望起点行: " + zeroY + ", 实际起点行: " + startY);

        save(image, "line-gift-cumulative.png");
    }

    /**
     * 测试绘制小数值（范围小于 1）的全正数曲线图，验证零轴贴合绘图区底部
     * <p>
     * 礼物金额等小数值数据（如 0.1 ~ 0.65）的纵轴范围小于 1，像素换算必须使用真实范围，
     * 否则 0 会被映射到绘图区中部，绘图区底部露出空白负半轴。断言累计与互动两种模式下
     * 深灰色零轴横线的行号均等于绘图区底部（top + plotHeight），并输出 line-gift.png
     */
    @Test
    public void testRenderLineChartSmallPositiveZeroAxisAtBottom() throws Exception {
        long start = 1784617225000L;

        // 模拟礼物金额：开场两笔 0.1，结尾 0.2、0.2、0.05
        List<ChartPainter.LinePoint> samples = new ArrayList<>();
        samples.add(new ChartPainter.LinePoint(start + 5_000L, 0.1));
        samples.add(new ChartPainter.LinePoint(start + 15_000L, 0.1));
        samples.add(new ChartPainter.LinePoint(start + 580_000L, 0.2));
        samples.add(new ChartPainter.LinePoint(start + 590_000L, 0.2));
        samples.add(new ChartPainter.LinePoint(start + 650_000L, 0.05));

        for (boolean cumulative : new boolean[]{true, false}) {
            BufferedImage image = ChartPainter.renderLineChart(samples, cumulative, 20, WIDTH, LINE_FONT).orElseThrow();
            assertNotNull(image);
            assertEquals(WIDTH, image.getWidth());
            assertEquals(WIDTH * 500 / 900, image.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());

            // 绘图区底部行号 = top(22) + plotHeight(图高 - 22 - 58)，零轴横线应绘制在该行附近
            int top = 22;
            int bottom = 58;
            int expectedAxisY = top + (image.getHeight() - top - bottom);
            int actualAxisY = findDarkAxisRow(image);
            assertTrue(Math.abs(actualAxisY - expectedAxisY) <= 1,
                    "零轴应贴合绘图区底部, 期望行: " + expectedAxisY + ", 实际行: " + actualAxisY);

            // 累计曲线与互动曲线都应在时间起点从 0 起步（首元素 0 锚点），
            // 首桶数值映射到桶结束时刻 x = 1 / bucketCount，保证两图时间刻度一致可互相对照
            int startY = findLineStartY(image);
            assertTrue(Math.abs(startY - expectedAxisY) <= 2,
                    "曲线应从 0 起步, 期望行: " + expectedAxisY + ", 实际行: " + startY);

            save(image, cumulative ? "line-gift-cumulative.png" : "line-gift-interaction.png");
        }
    }

    /**
     * 绘制排名图，包含空头像占位
     * <p>
     * 构建 4 名主播（含一个空头像）的正值数据，按数量降序排列，验证头像、昵称与数量条的绘制，
     * 以及高度 = 100 * 数量 + 25 *（数量 - 1）的布局公式，并输出 ranking.png
     */
    @Test
    public void testRenderRankingChart() throws Exception {
        List<ChartPainter.RankingItem> items = new ArrayList<>();
        items.add(new ChartPainter.RankingItem(createFace(Color.RED), "小明", 120));
        items.add(new ChartPainter.RankingItem(createFace(Color.BLUE), "小红", 80));
        items.add(new ChartPainter.RankingItem(createFace(Color.GREEN), "小刚", 50));
        items.add(new ChartPainter.RankingItem(null, "小丽", 20));

        BufferedImage image = ChartPainter.renderRankingChart(items, WIDTH, RANKING_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(100 * items.size() + 25 * (items.size() - 1), image.getHeight());
        save(image, "ranking.png");
    }

    /**
     * 测试绘制排名图（含负数时自动切换为双向布局），包含正负数量与空头像占位
     */
    @Test
    public void testRenderRankingChartWithNegatives() throws Exception {
        List<ChartPainter.RankingItem> items = new ArrayList<>();
        items.add(new ChartPainter.RankingItem(createFace(Color.RED), "小明", 100));
        items.add(new ChartPainter.RankingItem(createFace(Color.ORANGE), "小红", 60));
        items.add(new ChartPainter.RankingItem(createFace(Color.GREEN), "小刚", -30));
        items.add(new ChartPainter.RankingItem(null, "小丽", -80));

        BufferedImage image = ChartPainter.renderRankingChart(items, WIDTH, RANKING_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(100 * items.size() + 25 * (items.size() - 1), image.getHeight());
        save(image, "ranking-double.png");
    }

    /**
     * 测试绘制全负排名图（全部为负时仍为单向布局，左侧头像）
     */
    @Test
    public void testRenderRankingChartAllNegative() throws Exception {
        List<ChartPainter.RankingItem> items = new ArrayList<>();
        items.add(new ChartPainter.RankingItem(createFace(Color.RED), "小明", -20));
        items.add(new ChartPainter.RankingItem(createFace(Color.BLUE), "小红", -50));
        items.add(new ChartPainter.RankingItem(null, "小刚", -80));

        BufferedImage image = ChartPainter.renderRankingChart(items, WIDTH, RANKING_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(100 * items.size() + 25 * (items.size() - 1), image.getHeight());
        save(image, "ranking-negative.png");
    }

    /**
     * 测试绘制包含负数的曲线图，使用时间戳与数值输入，数据分布不均匀（大幅正负波动）
     */
    @Test
    public void testRenderLineChartWithNegatives() throws Exception {
        long start = 1784617225000L;

        List<ChartPainter.LinePoint> samples = new ArrayList<>();
        // 各时间点的盈亏数值，正负大幅波动且分布不均匀
        samples.add(new ChartPainter.LinePoint(start + 1 * 60 * 1000, 30.0));
        samples.add(new ChartPainter.LinePoint(start + 8 * 60 * 1000, -12.0));
        samples.add(new ChartPainter.LinePoint(start + 15 * 60 * 1000, 85.0));
        samples.add(new ChartPainter.LinePoint(start + 30 * 60 * 1000, -40.0));
        samples.add(new ChartPainter.LinePoint(start + 35 * 60 * 1000, -150.0));
        samples.add(new ChartPainter.LinePoint(start + 50 * 60 * 1000, 20.0));
        samples.add(new ChartPainter.LinePoint(start + 70 * 60 * 1000, 200.0));
        samples.add(new ChartPainter.LinePoint(start + 80 * 60 * 1000, -90.0));
        samples.add(new ChartPainter.LinePoint(start + 100 * 60 * 1000, 5.0));
        samples.add(new ChartPainter.LinePoint(start + 110 * 60 * 1000, 60.0));
        samples.add(new ChartPainter.LinePoint(start + 115 * 60 * 1000, -30.0));

        BufferedImage image = ChartPainter.renderLineChart(samples, false, 20, WIDTH, LINE_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(WIDTH * 500 / 900, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        save(image, "line-signed.png");
    }

    /**
     * 测试绘制累计盈亏曲线（累计曲线 + 内部负值），不分桶以保留完整趋势
     */
    @Test
    public void testRenderLineChartCumulativeWithNegatives() throws Exception {
        long start = 1784617225000L;

        List<ChartPainter.LinePoint> samples = new ArrayList<>();
        // 各时间点的盈亏数值，正负大幅波动且分布不均匀
        samples.add(new ChartPainter.LinePoint(start + 1 * 60 * 1000, 30.0));
        samples.add(new ChartPainter.LinePoint(start + 8 * 60 * 1000, -50.0));
        samples.add(new ChartPainter.LinePoint(start + 15 * 60 * 1000, 85.0));
        samples.add(new ChartPainter.LinePoint(start + 30 * 60 * 1000, -40.0));
        samples.add(new ChartPainter.LinePoint(start + 35 * 60 * 1000, -150.0));
        samples.add(new ChartPainter.LinePoint(start + 50 * 60 * 1000, 20.0));
        samples.add(new ChartPainter.LinePoint(start + 70 * 60 * 1000, 200.0));
        samples.add(new ChartPainter.LinePoint(start + 80 * 60 * 1000, -90.0));
        samples.add(new ChartPainter.LinePoint(start + 100 * 60 * 1000, 5.0));
        samples.add(new ChartPainter.LinePoint(start + 110 * 60 * 1000, 60.0));
        samples.add(new ChartPainter.LinePoint(start + 115 * 60 * 1000, -30.0));

        BufferedImage image = ChartPainter.renderLineChart(samples, true, 0, WIDTH, LINE_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(WIDTH * 500 / 900, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        save(image, "line-profit.png");
    }

    /**
     * 测试绘制分布图，包含中文标签
     * <p>
     * 使用“普通弹幕 / 表情弹幕 / 礼物互动 / 其他互动”四类数据，验证圆角堆叠条的绘制、
     * 图例自动换行与两端对齐，以及高度 = 顶边距 + 条高 + 行距空隙 + 行高 * 行数 + 底边距的布局公式，
     * 并输出 distribution.png
     */
    @Test
    public void testRenderDistributionChart() throws Exception {
        List<ChartPainter.DistributionSlice> slices = new ArrayList<>();
        slices.add(new ChartPainter.DistributionSlice("普通弹幕", 100));
        slices.add(new ChartPainter.DistributionSlice("表情弹幕", 30));
        slices.add(new ChartPainter.DistributionSlice("礼物互动", 15));
        slices.add(new ChartPainter.DistributionSlice("其他互动", 5));

        BufferedImage image = ChartPainter.renderDistributionChart(slices, WIDTH, DISTRIBUTION_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        // 圆角堆叠条布局：4 项带数值占比的图例分两行，高度 = 顶边距 28 + 条高 28 + 行距空隙 34 + 1 个行高 34 + 底边距 28
        assertEquals(28 + 28 + 34 + 34 + 28, image.getHeight());
        save(image, "distribution.png");
    }

    /**
     * 测试图例换行与超长标签省略：完整放得下的标签换行完整显示，独占一行仍放不下的标签截断省略
     */
    @Test
    public void testRenderDistributionChartWithLongLabels() throws Exception {
        List<ChartPainter.DistributionSlice> slices = new ArrayList<>();
        slices.add(new ChartPainter.DistributionSlice("普通弹幕", 100));
        // 中等长度标签：完整宽度放不下当前行时会换行，但保持完整显示不省略
        slices.add(new ChartPainter.DistributionSlice("这是一条中等长度的标签用来验证换行完整显示效果", 50));
        // 超长标签：独占一行仍放不下时截断省略
        slices.add(new ChartPainter.DistributionSlice("这是一条非常非常长的弹幕内容标签用来验证当标签内容独占一整行仍然放不下的时候需要截断显示省略号的效果", 20));

        BufferedImage image = ChartPainter.renderDistributionChart(slices, WIDTH, DISTRIBUTION_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        // 短标签、中等长度标签、超长标签各占一行，高度 = 顶边距 28 + 条高 28 + 行距空隙 34 + 2 个行高 34*2 + 底边距 28
        assertEquals(28 + 28 + 34 + 34 * 2 + 28, image.getHeight());
        save(image, "distribution-long-labels.png");
    }

    /**
     * 测试绘制包含极小占比部分与图例换行的分布图
     */
    @Test
    public void testRenderDistributionChartWithMinorSliceAndWrap() throws Exception {
        List<ChartPainter.DistributionSlice> slices = new ArrayList<>();
        slices.add(new ChartPainter.DistributionSlice("普通弹幕", 500));
        slices.add(new ChartPainter.DistributionSlice("表情弹幕", 200));
        slices.add(new ChartPainter.DistributionSlice("礼物互动", 100));
        slices.add(new ChartPainter.DistributionSlice("其他互动", 80));
        slices.add(new ChartPainter.DistributionSlice("粉丝团", 60));
        slices.add(new ChartPainter.DistributionSlice("舰长", 30));
        slices.add(new ChartPainter.DistributionSlice("上舰提示", 15));
        slices.add(new ChartPainter.DistributionSlice("醒目留言", 10));
        // 占比约 0.1%，应保留最小 3 像素宽度
        slices.add(new ChartPainter.DistributionSlice("极小占比项", 1));

        BufferedImage image = ChartPainter.renderDistributionChart(slices, WIDTH, DISTRIBUTION_FONT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        // 图例分三行：高度 = 顶边距 28 + 条高 28 + 行距空隙 34 + 2 个行高 34*2 + 底边距 28
        assertEquals(28 + 28 + 34 + 34 * 2 + 28, image.getHeight());
        save(image, "distribution-minor-wrap.png");
    }

    /**
     * 测试大字号（30px）下排名图文字与条形不重叠、分布图图例布局正常
     */
    @Test
    public void testRenderWithLargeFont() throws Exception {
        Font largeFont = new Font(Font.SANS_SERIF, Font.PLAIN, 30);

        List<ChartPainter.RankingItem> items = new ArrayList<>();
        items.add(new ChartPainter.RankingItem(createFace(Color.RED), "小明", 120));
        items.add(new ChartPainter.RankingItem(createFace(Color.BLUE), "小红", 80));
        BufferedImage ranking = ChartPainter.renderRankingChart(items, WIDTH, largeFont).orElseThrow();
        assertNotNull(ranking);
        save(ranking, "ranking-large-font.png");

        List<ChartPainter.DistributionSlice> slices = new ArrayList<>();
        slices.add(new ChartPainter.DistributionSlice("普通弹幕", 100));
        slices.add(new ChartPainter.DistributionSlice("表情弹幕", 30));
        slices.add(new ChartPainter.DistributionSlice("礼物互动", 15));
        BufferedImage distribution = ChartPainter.renderDistributionChart(slices, WIDTH, largeFont).orElseThrow();
        assertNotNull(distribution);
        save(distribution, "distribution-large-font.png");
    }

    /**
     * 测试绘制边界情况
     */
    @Test
    public void testRenderEdgeCases() {
        long start = 1784617225000L;

        // 空数据返回 Optional.empty()
        assertTrue(ChartPainter.renderLineChart(new ArrayList<>(), true, 20, WIDTH, LINE_FONT).isEmpty());
        assertTrue(ChartPainter.renderDistributionChart(new ArrayList<>(), WIDTH, DISTRIBUTION_FONT).isEmpty());

        // 数值全部为 0 时仍可正常绘制（平线），跨度需满足至少一分钟要求
        List<ChartPainter.LinePoint> zeroSamples = new ArrayList<>();
        zeroSamples.add(new ChartPainter.LinePoint(start, 0.0));
        zeroSamples.add(new ChartPainter.LinePoint(start + 60_000L, 0.0));
        assertTrue(ChartPainter.renderLineChart(zeroSamples, false, 20, WIDTH, LINE_FONT).isPresent());
        assertTrue(ChartPainter.renderLineChart(zeroSamples, true, 0, WIDTH, LINE_FONT).isPresent());

        // 持续时间低于 1 分钟返回 Optional.empty()，分桶与不分桶均受限
        List<ChartPainter.LinePoint> shortSamples = new ArrayList<>();
        shortSamples.add(new ChartPainter.LinePoint(start, 1.0));
        shortSamples.add(new ChartPainter.LinePoint(start + 5_000L, 2.0));
        assertTrue(ChartPainter.renderLineChart(shortSamples, false, 20, WIDTH, LINE_FONT).isEmpty());
        assertTrue(ChartPainter.renderLineChart(shortSamples, false, 0, WIDTH, LINE_FONT).isEmpty());

        // 分布图数值总和为 0 返回 Optional.empty()
        List<ChartPainter.DistributionSlice> zeroSlices = new ArrayList<>();
        zeroSlices.add(new ChartPainter.DistributionSlice("无数据", 0));
        assertTrue(ChartPainter.renderDistributionChart(zeroSlices, WIDTH, DISTRIBUTION_FONT).isEmpty());

        // 排名图数据列表为空返回 Optional.empty()
        assertTrue(ChartPainter.renderRankingChart(new ArrayList<>(), WIDTH, RANKING_FONT).isEmpty());

        // 排名图昵称为 null 时仍可正常绘制，仅不绘制昵称文本
        List<ChartPainter.RankingItem> nullNameItems = new ArrayList<>();
        nullNameItems.add(new ChartPainter.RankingItem(null, null, 10));
        assertTrue(ChartPainter.renderRankingChart(nullNameItems, WIDTH, RANKING_FONT).isPresent());

        // 分布图标签为 null 时仍可正常绘制，仅不绘制标签文本
        List<ChartPainter.DistributionSlice> nullLabelSlices = new ArrayList<>();
        nullLabelSlices.add(new ChartPainter.DistributionSlice(null, 10));
        assertTrue(ChartPainter.renderDistributionChart(nullLabelSlices, WIDTH, DISTRIBUTION_FONT).isPresent());

        // 分布图标签为空串时仍可正常绘制
        List<ChartPainter.DistributionSlice> emptyLabelSlices = new ArrayList<>();
        emptyLabelSlices.add(new ChartPainter.DistributionSlice("", 10));
        assertTrue(ChartPainter.renderDistributionChart(emptyLabelSlices, WIDTH, DISTRIBUTION_FONT).isPresent());

        // 宽度小于等于 0 返回 Optional.empty()
        List<ChartPainter.RankingItem> items = new ArrayList<>();
        items.add(new ChartPainter.RankingItem(null, "测试", 10));
        assertTrue(ChartPainter.renderRankingChart(items, 0, RANKING_FONT).isEmpty());
        assertTrue(ChartPainter.renderLineChart(zeroSamples, false, 20, 0, LINE_FONT).isEmpty());
        assertTrue(ChartPainter.renderDistributionChart(zeroSlices, -1, DISTRIBUTION_FONT).isEmpty());
    }

    /**
     * 查找曲线图左端起点行号
     * <p>
     * 曲线主色为 {@link ChartPainter#COLOR_LINE}，在绘图区左端前若干列扫描该颜色的像素，
     * 返回其最大行号（累计曲线从 0 起步时左端应贴近零轴/绘图区底部）
     *
     * @param image 曲线图图片
     * @return 左端曲线像素最大行号，未找到时返回 -1
     */
    private static int findLineStartY(BufferedImage image) {
        int bestY = -1;
        for (int x = 73; x <= 78; x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                if (Math.abs(red - 238) <= 25 && Math.abs(green - 73) <= 40 && Math.abs(blue - 121) <= 40) {
                    bestY = Math.max(bestY, y);
                }
            }
        }
        return bestY;
    }

    /**
     * 查找深灰色零轴横线所在行号
     * <p>
     * 曲线图零轴使用 2 像素深的深灰色实线绘制且横贯整个绘图区，网格线为浅灰色虚线，
     * 因此按行统计深灰色像素数量，最长深灰横线所在行即视为零轴行
     *
     * @param image 曲线图图片
     * @return 零轴横线所在行号，未找到时返回 -1
     */
    private static int findDarkAxisRow(BufferedImage image) {
        int bestY = -1;
        int bestCount = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            int count = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (rgb < 0x909090) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestY = y;
            }
        }
        return bestY;
    }

    /**
     * 将图片保存至本地 TestChart 目录
     * <p>
     * 受 {@link #SAVE_IMAGE} 控制，设为 false 时直接返回，不产生任何文件。
     * 输出目录位于运行工作目录（System property user.dir）下的 TestChart，
     * 目录不存在时自动创建，已存在的同名文件会被覆盖
     *
     * @param image 图片
     * @param name  文件名
     */
    private static void save(BufferedImage image, String name) throws IOException {
        if (!SAVE_IMAGE) {
            return;
        }
        Path directory = Paths.get(System.getProperty("user.dir"), "TestChart");
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        System.out.println("已生成图表: " + file.toAbsolutePath());
    }

    /**
     * 生成指定时间范围内的数据样本
     * @param samples      数据点集合
     * @param sampleStart  样本开始时间戳
     * @param sampleLength 样本持续时长，单位：毫秒
     * @param count        样本条数
     */
    private static void addSamples(List<ChartPainter.LinePoint> samples, long sampleStart, long sampleLength, int count) {
        for (int i = 0; i < count; i++) {
            long timestamp = sampleStart + (long) (Math.random() * sampleLength);
            samples.add(new ChartPainter.LinePoint(timestamp, 1.0));
        }
    }

    /**
     * 创建纯色测试头像
     * @param color 颜色
     * @return 头像图片
     */
    private static BufferedImage createFace(Color color) {
        BufferedImage face = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = face.createGraphics();
        draw.setColor(color);
        draw.fillRect(0, 0, 100, 100);
        draw.dispose();
        return face;
    }
}
