package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Font;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字体服务测试
 */
class FontServiceTest {
    /**
     * 优先使用自定义字体，再使用内置字体和系统字体
     */
    @Test
    void loadsConfiguredFontsBeforeFallbacks() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPaint().setFonts(List.of(Font.MONOSPACED));
        FontService fontService = new FontService(new DefaultResourceLoader(), properties);
        ReflectionTestUtils.invokeMethod(fontService, "init");

        String builtinFontName = fontService.parseFont("内置").orElseThrow().getName();
        assertThat(fontService.getFontNames())
                .startsWith(Font.MONOSPACED, builtinFontName)
                .endsWith(Font.SANS_SERIF);
        assertThat(fontService.findFontForCharacter('A').getName()).isEqualTo(Font.MONOSPACED);
        assertThat(properties.getPaint().getFonts()).containsExactly(Font.MONOSPACED);
    }
}
