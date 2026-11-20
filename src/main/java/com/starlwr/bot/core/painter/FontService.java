package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字体服务
 */
@Slf4j
@Service
public class FontService {
    private final ResourceLoader resourceLoader;

    private final StarBotCoreProperties properties;

    private Set<String> systemFonts = new HashSet<>();

    private final List<Font> fonts = new ArrayList<>();

    private final int DEFAULT_FONT_SIZE = 30;

    @Autowired
    public FontService(ResourceLoader resourceLoader, StarBotCoreProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    /**
     * 加载绘图使用的字体
     */
    @PostConstruct
    private void init() {
        // 获取当前系统已安装的字体
        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        systemFonts = Arrays.stream(graphicsEnvironment.getAvailableFontFamilyNames())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // 在自定义字体之后补充内置字体和当前系统的候选字体
        List<String> fontDefinitions = new ArrayList<>(properties.getPaint().getFonts());
        fontDefinitions.add("内置");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            fontDefinitions.addAll(List.of("微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial", "SansSerif"));
        } else if (os.contains("mac")) {
            fontDefinitions.addAll(List.of("PingFang SC", "Apple Color Emoji", "SansSerif"));
        } else {
            // sudo apt install -y  fonts-noto-cjk  fonts-wqy-zenhei  fonts-noto-color-emoji fonts-freefont-ttf
            fontDefinitions.addAll(List.of("Noto Sans CJK SC", "WenQuanYi Zen Hei", "Noto Color Emoji", "DejaVu Sans", "FreeSans", "SansSerif"));
        }

        // 根据候选顺序加载可用字体
        log.info("已指定使用字体列表: {}, 可使用配置项 starbot.core.paint.fonts 自定义字体列表", fontDefinitions);
        for (String fontDefinition : fontDefinitions) {
            parseFont(fontDefinition).ifPresent(fonts::add);
        }
    }

    /**
     * 解析字体
     * @param font 字体名称或路径
     * @return 字体
     */
    public Optional<Font> parseFont(String font) {
        try {
            if (systemFonts.contains(font.toLowerCase())) {
                return Optional.of(new Font(font, Font.PLAIN, DEFAULT_FONT_SIZE));
            } else if (font.toLowerCase().endsWith(".ttf")) {
                return Optional.of(Font.createFont(Font.TRUETYPE_FONT, Paths.get(font).toFile()).deriveFont(Font.PLAIN, DEFAULT_FONT_SIZE));
            } else if ("内置".equals(font)) {
                try (InputStream fontStream = resourceLoader.getResource("classpath:fonts/font.ttf").getInputStream()) {
                    return Optional.of(Font.createFont(Font.TRUETYPE_FONT, fontStream).deriveFont(Font.PLAIN, DEFAULT_FONT_SIZE));
                }
            } else {
                log.warn("{} 不在系统字体库中, 且不是一个有效的字体文件", font);
            }
        } catch (Exception e) {
            log.error("加载 {} 字体失败", font, e);
        }

        return Optional.empty();
    }

    /**
     * 获取当前已加载的字体名称列表
     *
     * @return 当前已加载的字体名称列表
     */
    public List<String> getFontNames() {
        return fonts.stream()
                .map(Font::getName)
                .collect(Collectors.toList());
    }

    /**
     * 查找可以显示指定字符的字体
     *
     * @param charCodePoint 字符编码
     * @return 可以显示该字符的字体
     */
    public Font findFontForCharacter(int charCodePoint) {
        return fonts.stream()
                .filter(font -> font.canDisplay(charCodePoint))
                .findFirst()
                .orElse(fonts.get(0));
    }

    /**
     * 计算指定字符串在 Graphics2D 中绘制时的像素宽度和高度
     *
     * @param draw 用于绘制文本的 Graphics2D 对象
     * @param text 要计算宽度和高度的含格式文本
     * @return 宽度，高度
     */
    public Pair<Integer, Integer> getStringWidthAndHeight(Graphics2D draw, TextWithStyle text) {
        if (StringUtils.isEmpty(text.getText())) {
            return Pair.of(0, 0);
        }

        int width = 0;
        int maxHeight = 0;

        Font originalFont = draw.getFont();

        if (text.getFont() != null) {
            draw.setFont(text.getFont().deriveFont(text.getStyle(), text.getSize()));
        }

        int[] codePoints = text.getText().codePoints().toArray();
        for (int codePoint : codePoints) {
            if (text.getFont() == null) {
                draw.setFont(findFontForCharacter(codePoint).deriveFont(text.getStyle(), text.getSize()));
            }

            String charAsString = new String(Character.toChars(codePoint));
            width += draw.getFontMetrics().stringWidth(charAsString);
            maxHeight = Math.max(maxHeight, draw.getFontMetrics().getHeight());
        }

        draw.setFont(originalFont);

        return Pair.of(width, maxHeight);
    }
}
