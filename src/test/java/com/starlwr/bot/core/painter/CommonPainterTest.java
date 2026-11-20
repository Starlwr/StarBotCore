package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 绘图器测试
 */
class CommonPainterTest {
    /**
     * 每个绘图器只读取一次配置，使用创建时的扩展高度
     */
    @Test
    void usesPaintSettingsFromConstruction() {
        StarBotCoreProperties.Paint original = new StarBotCoreProperties.Paint();
        original.setAutoExpandHeight(100);
        StarBotCoreProperties.Paint updated = new StarBotCoreProperties.Paint();
        updated.setAutoExpandHeight(200);
        StarBotCoreProperties properties = mock(StarBotCoreProperties.class);
        when(properties.getPaint()).thenReturn(original, updated);
        BuildProperties buildProperties = new BuildProperties(new Properties());
        FontService fontService = new FontService(new DefaultResourceLoader(), properties);

        CommonPainter first = new CommonPainter(buildProperties, properties, fontService, 100, 10, true);
        CommonPainter second = new CommonPainter(buildProperties, properties, fontService, 100, 10, true);
        first.expandHeightIfNeeded(15);
        second.expandHeightIfNeeded(15);

        assertEquals(110, first.getBufferedImage().getHeight());
        assertEquals(210, second.getBufferedImage().getHeight());
        verify(properties, times(2)).getPaint();
    }

    /**
     * 绘制额外版权文字时使用指定的样式，字号为 0 时保留字体原有字号
     * @param size 配置的字号
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 24})
    void drawsConfiguredCopyrightWithRequestedStyleAndSize(int size) {
        StarBotCoreProperties defaults = new StarBotCoreProperties();
        FontService fontService = new FontService(new DefaultResourceLoader(), defaults);
        ReflectionTestUtils.invokeMethod(fontService, "init");

        Font font = new Font(Font.SERIF, Font.PLAIN, 30);
        int style = Font.BOLD | Font.ITALIC;
        TextWithStyle configured = new TextWithStyle("Copyright", font, size, Color.BLACK, style);
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPaint().setExtraCopyrights(List.of(configured));

        int width = 800;
        int height = 240;
        int marginRight = 100;
        BuildProperties buildProperties = new BuildProperties(new Properties());
        CommonPainter actual = new CommonPainter(buildProperties, properties, fontService, width, height, false)
                .drawCopyright(marginRight);

        Font styledFont = size == 0 ? font.deriveFont(style) : font.deriveFont(style, size);
        TextWithStyle styled = new TextWithStyle("Copyright", styledFont, size, Color.BLACK, style);
        CommonPainter expected = new CommonPainter(buildProperties, defaults, fontService, width, height, false)
                .drawCopyright(List.of(), List.of(List.of(styled)), marginRight);

        assertEquals(expected.getY(), actual.getY());
        assertArrayEquals(expected.getImage().getRGB(0, 0, width, height, null, 0, width),
                actual.getImage().getRGB(0, 0, width, height, null, 0, width));
        assertEquals(font, configured.getFont());
    }
}
