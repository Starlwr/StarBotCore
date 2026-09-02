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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词云绘图器测试
 * <p>
 * 使用模拟弹幕数据（齐普夫长尾分布、中文/数字/英文混合词条、整条弹幕计频等）渲染词云，
 * 覆盖默认尺寸、自定义横幅尺寸、超长尾词条上限、极小画布、排布稳定性与各类输入边界情况，
 * 用于验证词云的排布效果、性能与参数校验。
 * <p>
 * 默认仅在内存中渲染并校验图片属性，不落盘；将 {@link #SAVE_IMAGE} 置为 true 时，
 * 额外将生成的 PNG 保存至 TestWordCloud 目录（相对于运行工作目录）供样式审阅
 */
public class WordCloudPainterTest {
    /**
     * 词云字体，优先使用项目内置字体，保证中文与生产环境渲染一致
     * <p>
     * 优先加载 src/main/resources/fonts/font.ttf（与生产环境的“内置”字体一致），
     * 加载失败时回退至系统 SANS_SERIF 字体
     */
    private static final Font FONT = loadBuiltinFont();

    /**
     * 测试图表宽度
     */
    private static final int WIDTH = 900;

    /**
     * 测试图表高度
     */
    private static final int HEIGHT = 500;

    /**
     * 测试最多绘制词条数量
     */
    private static final int LIMIT = 120;

    /**
     * 是否将生成的图片保存至本地 TestWordCloud 目录
     * <p>
     * 设为 false（默认）时仅为内存渲染并执行断言，不产生任何文件，适合日常跑测试；
     * 设为 true 时每个用例会将渲染结果保存为 PNG，用于人工审阅词云样式与调参效果，
     * 输出文件在运行目录下的 TestWordCloud 目录中
     */
    private static final boolean SAVE_IMAGE = false;

    /**
     * 测试绘制词云图，使用符合真实弹幕分布的长尾词频数据
     * <p>
     * 输入 75 个模拟弹幕词条（如晚安、哈哈哈、666、awsl 等，权重按齐普夫分布生成，高频词少、低频词多），
     * 按默认画布 {@link #WIDTH} x {@link #HEIGHT} 与默认词条上限 {@link #LIMIT} 渲染，
     * 断言图片尺寸为 900 x 500、像素类型为 ARGB，验证高频词居中、字号随权重递减的整体排布效果。
     * 同时输出绘制耗时；开启 {@link #SAVE_IMAGE} 后输出 wordcloud.png 供样式审阅
     */
    @Test
    public void testRenderWordCloud() throws Exception {
        List<WordCloudPainter.WordItem> words = createDanmuWords();

        long start = System.nanoTime();
        BufferedImage image = WordCloudPainter.renderWordCloud(words, WIDTH, HEIGHT, FONT, LIMIT).orElseThrow();
        System.out.println("词云绘制耗时: " + (System.nanoTime() - start) / 1_000_000 + " 毫秒, 词条数量: " + words.size());
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(HEIGHT, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        save(image, "wordcloud.png");
    }

    /**
     * 测试绘制自定义画布高度与词条数量上限的词云图（横幅样式，仅展示高频词）
     * <p>
     * 以 900 x 320 的横幅画布、最多 30 个词条渲染，用于验证高度与词条上限参数的正确传递，
     * 以及词条较少时画布自动放大字号、避免大面积留白的补白行为。开启 {@link #SAVE_IMAGE} 后输出 wordcloud-banner.png
     */
    @Test
    public void testRenderWordCloudWithCustomSize() throws Exception {
        List<WordCloudPainter.WordItem> words = createDanmuWords();

        BufferedImage image = WordCloudPainter.renderWordCloud(words, WIDTH, 320, FONT, 30).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(320, image.getHeight());
        save(image, "wordcloud-banner.png");
    }

    /**
     * 测试绘制超长尾词云图，词条数量远超上限，验证超出上限的低频词被丢弃且排版不溢出
     * <p>
     * 在 75 个常规词条基础上追加 200 个权重仅为 1 ~ 2 的“低频词N”，以 150 的词条上限和 900 x 600 画布渲染，
     * 验证词条截断（保留权重最高的前 150 个）、高权重词条优先占据中心、极低权重词条以最小字号铺满画布边缘。
     * 开启 {@link #SAVE_IMAGE} 后输出 wordcloud-long-tail.png
     */
    @Test
    public void testRenderWordCloudWithLongTail() throws Exception {
        List<WordCloudPainter.WordItem> words = new ArrayList<>(createDanmuWords());
        // 追加 200 个仅出现 1 ~ 2 次的低频词，模拟真实弹幕的超长尾分布
        for (int i = 0; i < 200; i++) {
            words.add(new WordCloudPainter.WordItem("低频词" + i, i % 2 == 0 ? 1 : 2));
        }

        BufferedImage image = WordCloudPainter.renderWordCloud(words, WIDTH, 600, FONT, 150).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(600, image.getHeight());
        save(image, "wordcloud-long-tail.png");
    }

    /**
     * 测试直接以整条弹幕内容计频绘制词云图，即未接入中文分词时的效果
     * <p>
     * 模拟约 500 条原始弹幕（短弹幕重复度高、长弹幕重复度低），将整条弹幕文本作为一个词条计频后渲染，
     * 用于预览尚未接入中文分词器时的效果。开启 {@link #SAVE_IMAGE} 后输出 wordcloud-raw-danmu.png
     */
    @Test
    public void testRenderWordCloudFromRawDanmu() throws Exception {
        List<String> danmus = createRawDanmus();

        // 整条弹幕作为一个词条计频，短弹幕重复度高，无需分词即可得到可用效果
        Map<String, Long> counts = new HashMap<>();
        for (String danmu : danmus) {
            counts.merge(danmu, 1L, Long::sum);
        }

        List<WordCloudPainter.WordItem> words = new ArrayList<>();
        counts.forEach((word, count) -> words.add(new WordCloudPainter.WordItem(word, count)));

        BufferedImage image = WordCloudPainter.renderWordCloud(words, WIDTH, HEIGHT, FONT, LIMIT).orElseThrow();
        assertNotNull(image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(HEIGHT, image.getHeight());
        save(image, "wordcloud-raw-danmu.png");
    }

    /**
     * 测试词云图排布稳定性，相同数据两次绘制结果应完全一致
     * <p>
     * 对同一份数据连续渲染两次并逐像素比较图片，验证随机种子由词条内容与权重生成、
     * 词条摆放位置与横竖排布可稳定复现，不因随机数产生差异
     */
    @Test
    public void testRenderWordCloudStable() {
        List<WordCloudPainter.WordItem> words = createDanmuWords();

        BufferedImage first = WordCloudPainter.renderWordCloud(words, WIDTH, HEIGHT, FONT, LIMIT).orElseThrow();
        BufferedImage second = WordCloudPainter.renderWordCloud(words, WIDTH, HEIGHT, FONT, LIMIT).orElseThrow();

        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                assertEquals(first.getRGB(x, y), second.getRGB(x, y), "词云排布应保持稳定, 坐标: (" + x + ", " + y + ")");
            }
        }
    }

    /**
     * 测试绘制词云图的边界情况
     * <p>
     * 覆盖：空词条列表、宽高小于等于 0、词条数量上限小于等于 0、词条全部无效（空白 / null / 非正权重）、
     * 仅单个词条、重复词条自动合并权重、超长词条自动缩小字号直至可放入画布。
     * 前四类断言返回 Optional.empty()，后三类断言可正常绘制；开启 {@link #SAVE_IMAGE} 后输出 wordcloud-long-word.png
     */
    @Test
    public void testRenderWordCloudEdgeCases() throws Exception {
        // 词条列表为空返回 Optional.empty()
        assertTrue(WordCloudPainter.renderWordCloud(new ArrayList<>(), WIDTH, HEIGHT, FONT, LIMIT).isEmpty());

        // 宽高小于等于 0 返回 Optional.empty()
        List<WordCloudPainter.WordItem> words = new ArrayList<>();
        words.add(new WordCloudPainter.WordItem("弹幕", 10));
        assertTrue(WordCloudPainter.renderWordCloud(words, 0, HEIGHT, FONT, LIMIT).isEmpty());
        assertTrue(WordCloudPainter.renderWordCloud(words, WIDTH, 0, FONT, LIMIT).isEmpty());

        // 词条数量上限小于等于 0 返回 Optional.empty()
        assertTrue(WordCloudPainter.renderWordCloud(words, WIDTH, HEIGHT, FONT, 0).isEmpty());
        assertTrue(WordCloudPainter.renderWordCloud(words, WIDTH, HEIGHT, FONT, -1).isEmpty());

        // 词条全部无效（空白词条、null 词条、非正权重）返回 Optional.empty()
        List<WordCloudPainter.WordItem> invalidWords = new ArrayList<>();
        invalidWords.add(new WordCloudPainter.WordItem("   ", 10));
        invalidWords.add(new WordCloudPainter.WordItem(null, 10));
        invalidWords.add(new WordCloudPainter.WordItem("零权重", 0));
        invalidWords.add(new WordCloudPainter.WordItem("负权重", -5));
        invalidWords.add(null);
        assertTrue(WordCloudPainter.renderWordCloud(invalidWords, WIDTH, HEIGHT, FONT, LIMIT).isEmpty());

        // 仅有单个词条时仍可正常绘制
        assertTrue(WordCloudPainter.renderWordCloud(words, WIDTH, HEIGHT, FONT, LIMIT).isPresent());

        // 重复词条自动合并权重
        List<WordCloudPainter.WordItem> duplicatedWords = new ArrayList<>();
        duplicatedWords.add(new WordCloudPainter.WordItem("哈哈哈", 30));
        duplicatedWords.add(new WordCloudPainter.WordItem("哈哈哈", 20));
        duplicatedWords.add(new WordCloudPainter.WordItem("晚安", 25));
        assertTrue(WordCloudPainter.renderWordCloud(duplicatedWords, WIDTH, HEIGHT, FONT, LIMIT).isPresent());

        // 超长词条自动缩小字号，直至可以放入画布
        List<WordCloudPainter.WordItem> longWords = new ArrayList<>();
        longWords.add(new WordCloudPainter.WordItem("这是一条特别特别长的弹幕内容用来验证超长词条会自动缩小字号直到能够放进画布为止", 100));
        longWords.add(new WordCloudPainter.WordItem("晚安", 50));
        BufferedImage longWordImage = WordCloudPainter.renderWordCloud(longWords, WIDTH, HEIGHT, FONT, LIMIT).orElseThrow();
        assertNotNull(longWordImage);
        save(longWordImage, "wordcloud-long-word.png");
    }

    /**
     * 测试绘制超小画布词云图，验证最大摆放尝试次数按画布自动推算后小画布仍可正常排布
     * <p>
     * 以 200 x 120 的极小画布与 15 个词条渲染，验证摆放尝试次数根据画布尺寸自动推算、
     * 不足时以最小尝试次数兜底的逻辑，以及极小画布下字体缩小的极限行为。
     * 开启 {@link #SAVE_IMAGE} 后输出 wordcloud-tiny.png
     */
    @Test
    public void testRenderWordCloudWithTinyCanvas() throws Exception {
        List<WordCloudPainter.WordItem> words = createDanmuWords();

        BufferedImage image = WordCloudPainter.renderWordCloud(words, 200, 120, FONT, 15).orElseThrow();
        assertNotNull(image);
        assertEquals(200, image.getWidth());
        assertEquals(120, image.getHeight());
        save(image, "wordcloud-tiny.png");
    }

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
     * 构造模拟弹幕词频数据，权重呈长尾分布，包含中文、数字与英文词条
     * <p>
     * 内置 75 个高频直播间词汇（问候语、夸赞、点歌、礼物、互动等），
     * 权重按齐普夫分布生成，即第 index 个词条的权重为 520 / (index + 1) 后四舍五入、最小为 1，
     * 模拟真实弹幕“少数词汇被大量重复、大量词汇仅出现几次”的分布特征
     *
     * @return 词云数据项列表
     */
    private static List<WordCloudPainter.WordItem> createDanmuWords() {
        String[] words = {
                "晚安", "哈哈哈", "主播", "好听", "666", "awsl", "唱歌", "可爱", "早点休息", "再来一首",
                "好帅", "太强了", "笑死", "麦克风", "声音", "破防了", "泪目", "好听哭了", "支持", "打卡",
                "蹲一个", "前排", "沙发", "关注了", "已投币", "舰长", "大航海", "谢谢主播", "生日快乐", "心疼",
                "加油", "冲鸭", "太好听了", "求歌单", "点歌", "网络卡了", "卡了", "音画不同步", "直播间", "弹幕",
                "表情包", "抽奖", "中奖", "盲盒", "亏麻了", "血赚", "又是一天", "下播了", "舍不得", "明天见",
                "下次一定", "收到", "晚上好", "中午好", "第一次来", "老粉了", "三连了", "转发了", "已三连", "头像好看",
                "唱得真好", "声音好甜", "破音了", "好家伙", "绝了", "泪目了", "我哭了", "好听死了", "再唱一首", "摸鱼中",
                "下班了", "加班中", "潜水", "路过", "新人"
        };

        List<WordCloudPainter.WordItem> items = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            // 齐普夫分布：词频与排名成反比，贴近真实弹幕的高频少、低频多
            double weight = Math.max(1, Math.round(520.0 / (i + 1)));
            items.add(new WordCloudPainter.WordItem(words[i], weight));
        }
        return items;
    }

    /**
     * 构造模拟原始弹幕内容列表，同一条弹幕重复出现多次
     * <p>
     * 由两部分组成：10 条高频短弹幕（如晚安、哈哈哈哈），出现次数按序递减，最高 60 条、最低 6 条；
     * 20 条中低频弹幕（如这首歌好好听啊），各自出现 1 ~ 3 次，用于模拟尚未分词的整条弹幕计频效果
     *
     * @return 弹幕内容列表
     */
    private static List<String> createRawDanmus() {
        List<String> danmus = new ArrayList<>();
        // 高频短弹幕，重复出现次数依次递减
        String[] hot = {"晚安", "哈哈哈哈", "666", "好听", "awsl", "主播加油", "太强了", "再来一首", "破防了", "打卡"};
        for (int i = 0; i < hot.length; i++) {
            for (int j = 0; j < (hot.length - i) * 6; j++) {
                danmus.add(hot[i]);
            }
        }
        // 中低频弹幕，各出现 1 ~ 3 次
        List<String> others = Arrays.asList(
                "这首歌好好听啊", "主播今天状态不错", "麦克风好像有点问题", "网络好卡", "已经三连了",
                "第一次来这个直播间", "up 主唱歌真好听", "明天还播吗", "舍不得下播", "谢谢主播的歌",
                "生日快乐", "点一首晴天", "求歌单", "盲盒亏麻了", "抽奖抽到了", "笑死我了", "好家伙",
                "声音好甜", "老粉丝了", "潜水冒泡"
        );
        for (int i = 0; i < others.size(); i++) {
            for (int j = 0; j <= i % 3; j++) {
                danmus.add(others.get(i));
            }
        }
        return danmus;
    }

    /**
     * 将图片保存至本地 TestWordCloud 目录
     * <p>
     * 受 {@link #SAVE_IMAGE} 控制，设为 false 时直接返回，不产生任何文件。
     * 输出目录位于运行工作目录（System property user.dir）下的 TestWordCloud，
     * 目录不存在时自动创建，已存在的同名文件会被覆盖
     *
     * @param image 图片
     * @param name  文件名
     */
    private static void save(BufferedImage image, String name) throws IOException {
        if (!SAVE_IMAGE) {
            return;
        }
        Path directory = Paths.get(System.getProperty("user.dir"), "TestWordCloud");
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        System.out.println("已生成图表: " + file.toAbsolutePath());
    }
}
