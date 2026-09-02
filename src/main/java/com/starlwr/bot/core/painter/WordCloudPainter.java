package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.util.StringUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 词云绘图器
 * <p>
 * 绘制流程：
 * <ol>
 *     <li>合并重复词条并过滤无效数据，按权重降序截取前 N 个词条</li>
 *     <li>按权重平方根线性映射计算每个词条的字号</li>
 *     <li>取词条的字形轮廓，栅格化为按 bit 存储的低分辨率占位图（下称词条位图）</li>
 *     <li>沿阿基米德螺线由画布中心向外搜索空位，使用位运算按位与快速检测碰撞</li>
 *     <li>词条已全部放入但画布过于空旷时，放大字号重新排布</li>
 * </ol>
 * <p>
 * 坐标系说明：绘制使用像素坐标，碰撞检测使用网格坐标。网格是将画布按 {@link #GRID_SIZE} 像素切分后的低分辨率棋盘，
 * 每个网格占用 1 个 bit，凡是单位标注为“网格”的参数，乘以 {@link #GRID_SIZE} 即为像素值
 */
@Slf4j
public class WordCloudPainter {
    /**
     * 词云调色板
     * <p>
     * 按词条权重降序依次轮换取色。
     * 颜色数量越多整体越花哨，越少则越统一
     */
    private static final Color[] PALETTE = {
            new Color(121, 86, 237), new Color(238, 73, 121), new Color(55, 187, 248),
            new Color(255, 145, 41), new Color(45, 160, 75), new Color(157, 96, 232)
    };

    /**
     * 碰撞检测网格边长，单位：像素
     * <p>
     * 碰撞检测不在像素级进行，而是将画布按该边长切分为网格，每个网格用 1 个 bit 记录是否已被占用。
     * 词条位图降采样时，只要网格内存在任意墨迹像素即视为整格占用，因此网格边长越大，词条被“膨胀”得越明显。
     * 调大：占用内存与检测耗时按平方级下降（边长翻倍则耗时约降至四分之一），但词条落点会对齐到更粗的网格，间距误差变大，排布更松散；
     * 调小：精度更高、排布更紧凑，取 1 时为像素级精度，但内存与耗时上升约 4 倍。
     * 推荐范围：1 ~ 3
     */

    private static final int GRID_SIZE = 3;

    /**
     * 词条外扩间距，单位：像素
     * <p>
     * 生成词条位图前，会将字形轮廓的包围盒四周各外扩该像素数。由于每个词条都自带一圈间距，
     * 相邻两个词条的实际间隙约为该值的两倍，再减去最多 {@link #GRID_SIZE} - 1 像素的网格量化误差。
     * 调大：排版更通透，但同样面积可容纳的词条数量减少；
     * 调小：排版更紧凑，取 0 时相邻词条的笔画可能粘连。
     * 推荐范围：2 ~ 6
     */
    private static final int PADDING = 5;

    /**
     * 螺线每旋转一圈的半径增量，单位：网格
     * <p>
     * 词条摆放位置沿阿基米德螺线由内向外搜索，该值决定螺线相邻两圈之间的径向间距。
     * 调小：螺线更密集，更容易发现狭小空隙，排布更紧凑，但采样点数量与耗时按反比上升；
     * 调大：搜索更快，但容易跨过可用空隙，成品会出现明显空洞。
     * 推荐范围：2 ~ 4
     */
    private static final double SPIRAL_STEP = 3.0;

    /**
     * 螺线相邻采样点的弧长间距，单位：网格
     * <p>
     * 螺线上每前进该弧长取一个候选位置，实现方式为角度增量 = 该值 / 当前半径，
     * 使内圈与外圈的采样密度保持一致，避免外圈相邻候选位置之间间隔过大而漏掉可用空位。
     * 调小：候选位置更多，排布更紧凑，耗时按反比上升；
     * 调大：搜索更快，但词条容易错过狭窄空隙而被丢弃。
     * 推荐范围：1 ~ 2
     */
    private static final double SPIRAL_ARC = 1.5;

    /**
     * 螺线最大搜索半径与画布网格高度的比值
     * <p>
     * 螺线已按画布宽高比横向拉伸，因此该比值只需覆盖纵向即可同时覆盖横向，覆盖画布四角所需的比值为 0.5，
     * 此处取 0.75 留出余量。
     * 调大：搜索范围扩大，但超出画布的候选位置会被直接跳过，仅增加无效耗时；
     * 调小：外圈区域搜索不到，画布边缘会留下空白
     */
    private static final double SEARCH_RADIUS_RATIO = 0.75;

    /**
     * 单个词条最大摆放尝试次数的冗余系数
     * <p>
     * 单个词条的最大摆放尝试次数按画布尺寸自动推算，无需手动维护：走完整条螺线所需的采样点数量
     * 约等于螺线扫过的面积除以单个采样点代表的面积，即 π * 最大搜索半径² / ({@link #SPIRAL_STEP} * {@link #SPIRAL_ARC})，
     * 再乘以该系数作为冗余，保证任意画布尺寸下螺线都能完整走完，否则画布外圈将永远搜索不到。
     * 单次尝试仅包含数次位运算且可提前退出，因此该系数偏大也不会明显影响性能
     */
    private static final double ATTEMPT_FACTOR = 1.2;

    /**
     * 单个词条的最少摆放尝试次数
     * <p>
     * 画布极小时自动推算出的尝试次数可能过少，此处作为兜底下限，避免词条因尝试次数不足被误丢弃
     */
    private static final int MIN_ATTEMPTS = 1000;

    /**
     * 螺线起点相对画布中心的随机抖动比例
     * <p>
     * 每个词条的搜索起点会在画布中心附近按画布尺寸的正负一半该比例随机偏移，
     * 避免所有词条沿同一条螺线摆放而在成品上形成规则纹路。
     * 调大：排布更随机自然，但高权重词条可能偏离画布中心；
     * 调小：高权重词条更居中，但容易出现螺线纹路
     */
    private static final double CENTER_JITTER_RATE = 0.06;

    /**
     * 竖排词条比例
     * <p>
     * 除权重最高的词条固定横排外，其余词条按该概率顺时针旋转 90 度竖排展示。
     * 随机种子由词条内容与权重生成，因此同一份数据每次绘制的横竖分布完全一致。
     * 调大：更容易填满横向空隙，观感更接近典型词云，但需要侧头阅读的词条变多；
     * 调小：可读性更好，取 0 时全部横排，但空隙更多。
     * 推荐范围：0.15 ~ 0.3
     */
    private static final double VERTICAL_RATE = 0.2;

    /**
     * 最大字号与画布高度的比值倒数
     * <p>
     * 最大字号取画布高度的该分之一与画布宽度的 {@link #MAX_FONT_SIZE_WIDTH_RATIO} 分之一中的较小值，
     * 保证最高权重词条既不会高到占满画布，也不会宽到放不下。
     * 调小（如 4）：最高权重词条更醒目；调大（如 8）：整体字号更均匀
     */
    private static final int MAX_FONT_SIZE_HEIGHT_RATIO = 5;

    /**
     * 最大字号与画布宽度的比值倒数
     * <p>
     * 含义同 {@link #MAX_FONT_SIZE_HEIGHT_RATIO}，用于限制横排长词条的宽度
     */
    private static final int MAX_FONT_SIZE_WIDTH_RATIO = 10;

    /**
     * 最大字号的下限，单位：像素
     * <p>
     * 画布过小时按比例算出的最大字号会过小，此处作为兜底，保证最高权重词条仍然可读
     */
    private static final int MIN_MAX_FONT_SIZE = 24;

    /**
     * 最小字号与最大字号的比值倒数
     * <p>
     * 最小字号取最大字号的该分之一，即字号动态范围默认为 5 倍。
     * 调小（如 3）：高低权重词条的字号差距缩小，画面更均匀；
     * 调大（如 8）：字号对比更强烈，低权重词条更小更密
     */
    private static final int MIN_FONT_SIZE_RATIO = 5;

    /**
     * 最小字号的下限，单位：像素
     * <p>
     * 低于该字号的中文基本无法辨认，因此作为硬下限
     */
    private static final int MIN_FONT_SIZE = 12;

    /**
     * 词条尺寸超出画布时每次缩小的字号，单位：像素
     * <p>
     * 超长词条会按该步长逐步缩小字号重试，直至可以放入画布或触达最小字号。
     * 调小：更贴近画布可容纳的最大字号，但重试次数增加
     */
    private static final int FONT_SIZE_SHRINK_STEP = 2;

    /**
     * 目标墨迹占比，范围：0 ~ 1
     * <p>
     * 排布结束后统计所有已摆放词条的字形墨迹像素数与画布像素总数的比值，词条已全部放入但该比值低于目标值时，
     * 说明画布过于空旷，会放大字号重新排布。
     * 统计的是字形本身的墨迹，不含 {@link #PADDING} 外扩出的间距，因此该值与肉眼看到的疏密程度一致，
     * 且不会随 {@link #PADDING}、{@link #GRID_SIZE} 的调整而漂移。
     * 注意该值是放大字号的触发条件而非保证值：一旦放大字号后无法容纳全部词条，即停止放大并保留上一次的排布结果，
     * 因此词条较多时最终墨迹占比通常低于该值。
     * 调大：成品更饱满、字号更大，但重排次数与耗时上升；
     * 调小：几乎不触发重排，词条较少时画布会明显空旷。
     * 推荐范围：0.25 ~ 0.35
     */
    private static final double TARGET_COVERAGE = 0.3;

    /**
     * 放大字号重新排布的次数上限
     * <p>
     * 每次重排都是一次完整的重新排布，900 x 500 画布下单次耗时约 50 ~ 100 毫秒，
     * 因此该值直接决定最坏情况下的绘制耗时（最多 1 + 该值 次排布）。
     * 结合 {@link #RETRY_SCALE}，字号最多被放大 {@link #RETRY_SCALE} 的该次方倍
     */
    private static final int MAX_RETRIES = 3;

    /**
     * 每次重新排布的字号放大倍率
     * <p>
     * 每次重排将最大字号乘以该倍率，最小字号随之等比放大。
     * 任意一次重排若导致词条无法全部放入，则立即停止并保留上一次的排布结果。
     * 调大：更快逼近目标占用率，但步长过大容易“一次跨过头”导致回退，最终反而偏空；
     * 调小：逼近更细腻，但需要更多次重排。
     * 推荐范围：1.2 ~ 1.35
     */
    private static final double RETRY_SCALE = 1.3;

    /**
     * 低权重词条的最大混白比例，范围：0 ~ 1
     * <p>
     * 词条颜色在调色板基础色上按权重比例混合白色，权重最低的词条混合比例即为该值，权重最高的词条不混合。
     * 调大：高低权重词条的深浅对比更强烈，低权重词条更淡；
     * 调小：整体颜色更饱和统一
     */
    private static final double COLOR_BLEND_RATE = 0.45;

    private WordCloudPainter() {
    }

    /**
     * 词云数据项
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class WordItem {
        /**
         * 词条
         */
        private String word;

        /**
         * 权重，通常为词条出现次数
         */
        private double weight;
    }

    /**
     * 绘制词云图
     * <p>
     * 词条按权重降序依次摆放，字号由权重平方根线性映射得出，高权重词条优先占据画布中心，
     * 摆放位置沿阿基米德螺线由内向外搜索，碰撞检测使用降采样位图按位与实现。
     * 词条已全部放入但画布过于空旷时自动放大字号重新排布，画布空间不足时自动跳过剩余低权重词条
     *
     * @param words  词云数据项列表
     * @param width  图表宽度
     * @param height 图表高度
     * @param font   文字字体
     * @param limit  词条数量上限
     * @return 词云图图片
     */
    public static Optional<BufferedImage> renderWordCloud(@NonNull List<WordItem> words, int width, int height, @NonNull Font font, int limit) {
        if (CollectionUtils.isEmpty(words)) {
            log.warn("绘制词云图失败, 词条列表不能为空");
            return Optional.empty();
        }

        if (width <= 0 || height <= 0) {
            log.warn("绘制词云图失败, 图表宽高必须大于 0");
            return Optional.empty();
        }

        if (limit <= 0) {
            log.warn("绘制词云图失败, 词条数量上限必须大于 0");
            return Optional.empty();
        }

        // 合并重复词条，过滤空白词条与非正权重词条
        Map<String, Double> merged = new LinkedHashMap<>();
        for (WordItem item : words) {
            if (item == null || StringUtil.isBlank(item.getWord()) || item.getWeight() <= 0) {
                continue;
            }
            merged.merge(item.getWord().trim(), item.getWeight(), Double::sum);
        }

        if (merged.isEmpty()) {
            log.warn("绘制词云图失败, 有效词条列表不能为空");
            return Optional.empty();
        }

        // 按权重降序排列，权重高的词条优先摆放，保证高权重词条位于画布中心
        List<WordItem> sorted = merged.entrySet().stream()
                .map(entry -> new WordItem(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(WordItem::getWeight).reversed())
                .limit(limit)
                .toList();

        // 字号上限按画布尺寸推算，词条数量较少时后续按占用率放大
        int baseMaxFontSize = Math.max(MIN_MAX_FONT_SIZE, Math.min(height / MAX_FONT_SIZE_HEIGHT_RATIO, width / MAX_FONT_SIZE_WIDTH_RATIO));
        // 随机种子由词条内容与权重生成，保证相同数据多次绘制的排布结果一致
        long seed = seed(sorted);
        FontRenderContext context = new FontRenderContext(null, true, true);

        // 先按基准字号排布，词条已全部放入但画布过于空旷时逐步放大字号重新排布
        WordCloudLayout layout = layout(sorted, width, height, font, baseMaxFontSize, context, seed);
        double fontScale = 1.0;
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            if (layout.getPlaced() < sorted.size() || layout.getCoverage() >= TARGET_COVERAGE) {
                break;
            }

            fontScale *= RETRY_SCALE;
            WordCloudLayout candidate = layout(sorted, width, height, font, (int) Math.round(baseMaxFontSize * fontScale), context, seed);
            // 放大字号后无法容纳全部词条时，保留上一次的排布结果
            if (candidate.getPlaced() < sorted.size()) {
                break;
            }
            layout = candidate;
        }

        if (layout.getPlaced() == 0) {
            log.warn("绘制词云图失败, 画布空间不足, 没有任何词条可以放入");
            return Optional.empty();
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = image.createGraphics();
        quality(draw);
        draw.setColor(Color.WHITE);
        draw.fillRect(0, 0, width, height);

        for (PlacedWord word : layout.getWords()) {
            draw.setColor(word.getColor());
            draw.fill(word.getShape());
        }

        draw.dispose();
        return Optional.of(image);
    }

    /**
     * 排布词云词条，按权重降序依次沿螺线摆放，并统计画布网格占用率
     *
     * @param words       按权重降序排列的词云数据项列表
     * @param width       图表宽度
     * @param height      图表高度
     * @param font        文字字体
     * @param maxFontSize 最大字号
     * @param context     字体渲染上下文
     * @param seed        随机种子
     * @return 排布结果
     */
    private static WordCloudLayout layout(List<WordItem> words, int width, int height, Font font, int maxFontSize, FontRenderContext context, long seed) {
        int minFontSize = Math.max(MIN_FONT_SIZE, maxFontSize / MIN_FONT_SIZE_RATIO);

        // 字号按权重平方根线性映射，相比线性映射可避免高权重词条过度放大、低权重词条全部挤为最小字号
        double maxWeightScale = Math.sqrt(words.get(0).getWeight());
        double minWeightScale = Math.sqrt(words.get(words.size() - 1).getWeight());

        // 碰撞检测位图，每个 bit 代表一个网格，每行额外补 2 个 long 供位移进位写入，避免越界
        int gridWidth = (width + GRID_SIZE - 1) / GRID_SIZE;
        int gridHeight = (height + GRID_SIZE - 1) / GRID_SIZE;
        int stride = ((gridWidth + 63) >>> 6) + 2;
        long[] board = new long[gridHeight * stride];

        // 螺线最大搜索半径，以及走完整条螺线所需的采样点数量，即单个词条的最大摆放尝试次数
        double maxRadius = gridHeight * SEARCH_RADIUS_RATIO;
        int maxAttempts = (int) Math.max(MIN_ATTEMPTS, Math.ceil(Math.PI * maxRadius * maxRadius / (SPIRAL_STEP * SPIRAL_ARC) * ATTEMPT_FACTOR));

        Random random = new Random(seed);
        List<PlacedWord> placedWords = new ArrayList<>();
        long inkPixels = 0;

        for (int index = 0; index < words.size(); index++) {
            WordItem item = words.get(index);
            double scale = maxWeightScale > minWeightScale ? (Math.sqrt(item.getWeight()) - minWeightScale) / (maxWeightScale - minWeightScale) : 0.5;
            int fontSize = (int) Math.round(minFontSize + (maxFontSize - minFontSize) * scale);
            // 首个词条为最高权重词条，固定横排展示，其余词条按比例随机竖排，提升排版紧凑度
            boolean vertical = index > 0 && random.nextDouble() < VERTICAL_RATE;

            // 词条尺寸超出画布时逐步缩小字号重试
            WordSprite sprite = null;
            while (fontSize >= minFontSize) {
                WordSprite candidate = createWordSprite(item.getWord(), font.deriveFont(Font.PLAIN, (float) fontSize), vertical, context);
                if (candidate == null) {
                    break;
                }
                if (candidate.getGridWidth() <= gridWidth && candidate.getGridHeight() <= gridHeight) {
                    sprite = candidate;
                    break;
                }
                fontSize -= FONT_SIZE_SHRINK_STEP;
            }

            if (sprite == null) {
                continue;
            }

            Point position = findPosition(board, stride, gridWidth, gridHeight, sprite, random, maxRadius, maxAttempts);
            if (position == null) {
                continue;
            }
            occupy(board, stride, sprite, position.x, position.y);

            // 网格坐标换算回像素坐标，并将词条轮廓平移至目标位置
            double offsetX = (double) position.x * GRID_SIZE + PADDING - sprite.getBounds().x;
            double offsetY = (double) position.y * GRID_SIZE + PADDING - sprite.getBounds().y;
            Shape shape = AffineTransform.getTranslateInstance(offsetX, offsetY).createTransformedShape(sprite.getOutline());
            placedWords.add(new PlacedWord(shape, wordColor(index, scale)));
            inkPixels += sprite.getInkPixels();
        }

        // 墨迹占比为已摆放词条的字形墨迹面积与画布面积之比，用于判断画布是否过于空旷
        return new WordCloudLayout(placedWords, (double) inkPixels / ((long) width * height));
    }

    /**
     * 生成词条位图
     *
     * @param word     词条
     * @param font     词条字体，字号已按权重缩放
     * @param vertical 是否竖排展示
     * @param context  字体渲染上下文
     * @return 词条位图，词条无有效轮廓时返回 null
     */
    private static WordSprite createWordSprite(String word, Font font, boolean vertical, FontRenderContext context) {
        GlyphVector glyphs = font.createGlyphVector(context, word);
        Shape outline = glyphs.getOutline();
        if (vertical) {
            // 顺时针旋转 90 度实现自上而下的竖排展示
            outline = AffineTransform.getRotateInstance(Math.PI / 2).createTransformedShape(outline);
        }

        Rectangle bounds = outline.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }

        // 轮廓四周外扩间距后渲染为灰度掩码，间距用于保证词条之间不会紧贴
        int maskWidth = bounds.width + PADDING * 2;
        int maskHeight = bounds.height + PADDING * 2;
        BufferedImage mask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D draw = mask.createGraphics();
        quality(draw);
        Shape shape = AffineTransform.getTranslateInstance(PADDING - bounds.x, PADDING - bounds.y).createTransformedShape(outline);
        if (PADDING > 0) {
            // 以 2 倍间距为线宽沿字形轮廓描边，使掩码在字形基础上整体向外膨胀一圈间距，
            // 从而让碰撞检测把间距一并纳入判定，保证词条之间不会紧贴。
            // 膨胀部分以灰色绘制，字形本体随后以白色覆盖，便于在同一次扫描中区分二者
            draw.setColor(Color.GRAY);
            draw.setStroke(new BasicStroke(PADDING * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw.draw(shape);
        }
        draw.setColor(Color.WHITE);
        draw.fill(shape);
        draw.dispose();

        int gridWidth = (maskWidth + GRID_SIZE - 1) / GRID_SIZE;
        int gridHeight = (maskHeight + GRID_SIZE - 1) / GRID_SIZE;
        int stride = (gridWidth + 63) >>> 6;
        long[] bits = new long[gridHeight * stride];

        // 按网格降采样，网格内存在任意非空像素即视为占用，同时统计纯白像素数量作为字形墨迹面积
        int inkPixels = 0;
        Raster raster = mask.getRaster();
        for (int y = 0; y < maskHeight; y++) {
            int row = (y / GRID_SIZE) * stride;
            for (int x = 0; x < maskWidth; x++) {
                int sample = raster.getSample(x, y, 0);
                if (sample > 0) {
                    int column = x / GRID_SIZE;
                    bits[row + (column >>> 6)] |= 1L << (column & 63);
                }
                if (sample >= 255) {
                    inkPixels++;
                }
            }
        }

        return new WordSprite(outline, bounds, bits, stride, gridWidth, gridHeight, inkPixels);
    }

    /**
     * 沿阿基米德螺线由内向外搜索词条的可用摆放位置
     *
     * @param board       画布占位位图
     * @param stride      画布位图每行占用的 long 数量
     * @param gridWidth   画布宽度，单位：网格
     * @param gridHeight  画布高度，单位：网格
     * @param sprite      词条位图
     * @param random      随机数生成器
     * @param maxRadius   最大搜索半径，单位：网格
     * @param maxAttempts 最大尝试次数
     * @return 词条左上角网格坐标，无可用位置时返回 null
     */
    private static Point findPosition(long[] board, int stride, int gridWidth, int gridHeight, WordSprite sprite, Random random, double maxRadius, int maxAttempts) {
        // 起点在画布中心附近小幅随机偏移，避免所有词条沿同一条螺线堆叠出明显纹路
        double centerX = gridWidth / 2.0 + (random.nextDouble() - 0.5) * gridWidth * CENTER_JITTER_RATE;
        double centerY = gridHeight / 2.0 + (random.nextDouble() - 0.5) * gridHeight * CENTER_JITTER_RATE;
        // 螺线按画布宽高比横向拉伸，使词云整体形状贴合画布
        double aspect = (double) gridWidth / gridHeight;
        double angle = random.nextDouble() * Math.PI * 2;
        double startAngle = angle;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double radius = SPIRAL_STEP * (angle - startAngle) / (Math.PI * 2);
            if (radius > maxRadius) {
                break;
            }

            int x = (int) Math.round(centerX + radius * aspect * Math.cos(angle) - sprite.getGridWidth() / 2.0);
            int y = (int) Math.round(centerY + radius * Math.sin(angle) - sprite.getGridHeight() / 2.0);
            // 角度步长与半径成反比，保证螺线上相邻采样点的间距稳定
            angle += Math.max(0.02, SPIRAL_ARC / Math.max(radius, 1.0));

            if (x < 0 || y < 0 || x + sprite.getGridWidth() > gridWidth || y + sprite.getGridHeight() > gridHeight) {
                continue;
            }

            if (!collides(board, stride, sprite, x, y)) {
                return new Point(x, y);
            }
        }

        return null;
    }

    /**
     * 检测词条位图在指定位置是否与画布已占位区域重叠
     *
     * @param board  画布占位位图
     * @param stride 画布位图每行占用的 long 数量
     * @param sprite 词条位图
     * @param x      词条左上角网格横坐标
     * @param y      词条左上角网格纵坐标
     * @return 是否重叠
     */
    private static boolean collides(long[] board, int stride, WordSprite sprite, int x, int y) {
        int shift = x & 63;
        int offset = x >>> 6;

        for (int row = 0; row < sprite.getGridHeight(); row++) {
            int boardIndex = (y + row) * stride + offset;
            int spriteIndex = row * sprite.getStride();
            long carry = 0;
            for (int column = 0; column < sprite.getStride(); column++) {
                long value = sprite.getBits()[spriteIndex + column];
                if ((board[boardIndex + column] & ((value << shift) | carry)) != 0) {
                    return true;
                }
                // 左移溢出的高位进位到下一个 long
                carry = shift == 0 ? 0 : (value >>> (64 - shift));
            }
            if (carry != 0 && (board[boardIndex + sprite.getStride()] & carry) != 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * 将词条位图写入画布占位位图
     *
     * @param board  画布占位位图
     * @param stride 画布位图每行占用的 long 数量
     * @param sprite 词条位图
     * @param x      词条左上角网格横坐标
     * @param y      词条左上角网格纵坐标
     */
    private static void occupy(long[] board, int stride, WordSprite sprite, int x, int y) {
        int shift = x & 63;
        int offset = x >>> 6;

        for (int row = 0; row < sprite.getGridHeight(); row++) {
            int boardIndex = (y + row) * stride + offset;
            int spriteIndex = row * sprite.getStride();
            long carry = 0;
            for (int column = 0; column < sprite.getStride(); column++) {
                long value = sprite.getBits()[spriteIndex + column];
                board[boardIndex + column] |= (value << shift) | carry;
                carry = shift == 0 ? 0 : (value >>> (64 - shift));
            }
            if (carry != 0) {
                board[boardIndex + sprite.getStride()] |= carry;
            }
        }
    }

    /**
     * 计算词条颜色，按顺序轮换调色板色相，并按权重比例混合白色，权重越低颜色越浅
     *
     * @param index 词条序号
     * @param scale 权重比例，范围：0 ~ 1
     * @return 词条颜色
     */
    private static Color wordColor(int index, double scale) {
        Color base = PALETTE[index % PALETTE.length];
        double blend = COLOR_BLEND_RATE * (1 - Math.max(0, Math.min(1, scale)));
        int red = (int) Math.round(base.getRed() + (255 - base.getRed()) * blend);
        int green = (int) Math.round(base.getGreen() + (255 - base.getGreen()) * blend);
        int blue = (int) Math.round(base.getBlue() + (255 - base.getBlue()) * blend);
        return new Color(red, green, blue);
    }

    /**
     * 由词条内容与权重生成随机种子，保证相同数据的排布结果稳定可复现
     *
     * @param words 词云数据项列表
     * @return 随机种子
     */
    private static long seed(List<WordItem> words) {
        long seed = 17;
        for (WordItem item : words) {
            seed = seed * 31 + item.getWord().hashCode();
            seed = seed * 31 + Double.hashCode(item.getWeight());
        }
        return seed;
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
     * 词云排布结果
     */
    @Getter
    @AllArgsConstructor
    private static class WordCloudLayout {
        /**
         * 已摆放的词条列表
         */
        private final List<PlacedWord> words;

        /**
         * 画布墨迹占比，即已摆放词条的字形墨迹面积与画布面积之比，范围：0 ~ 1
         */
        private final double coverage;

        /**
         * 获取已摆放的词条数量
         *
         * @return 已摆放的词条数量
         */
        private int getPlaced() {
            return words.size();
        }
    }

    /**
     * 已完成摆放的词云词条
     */
    @Getter
    @AllArgsConstructor
    private static class PlacedWord {
        /**
         * 已平移至目标位置的词条轮廓
         */
        private final Shape shape;

        /**
         * 词条颜色
         */
        private final Color color;
    }

    /**
     * 词云词条位图，将词条轮廓降采样为按 bit 存储的占位图，用于快速碰撞检测
     */
    @Getter
    @AllArgsConstructor
    private static class WordSprite {
        /**
         * 词条轮廓
         */
        private final Shape outline;

        /**
         * 词条轮廓包围盒
         */
        private final Rectangle bounds;

        /**
         * 位图数据，每个 bit 代表一个网格，置 1 表示该网格被词条占用
         */
        private final long[] bits;

        /**
         * 位图每行占用的 long 数量
         */
        private final int stride;

        /**
         * 位图宽度，单位：网格
         */
        private final int gridWidth;

        /**
         * 位图高度，单位：网格
         */
        private final int gridHeight;

        /**
         * 字形墨迹像素数量，不含外扩间距，用于统计画布墨迹占比
         */
        private final int inkPixels;
    }
}
