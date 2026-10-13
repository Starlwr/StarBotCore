package com.starlwr.bot.core.painter;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * StarBot 绘图器工厂
 */
@Component
public class StarBotCommonPainterFactory {
    private final BuildProperties buildProperties;

    private final StarBotCoreProperties properties;

    private final FontService fontService;

    @Autowired
    public StarBotCommonPainterFactory(BuildProperties buildProperties, StarBotCoreProperties properties, FontService fontService) {
        this.buildProperties = buildProperties;
        this.properties = properties;
        this.fontService = fontService;
    }

    /**
     * 创建绘图器
     * @param width 画布宽度
     * @param height 画布高度
     * @return 绘图器
     */
    public CommonPainter create(int width, int height) {
        return create(width, height, false);
    }

    /**
     * 创建绘图器
     * @param width 画布宽度
     * @param height 画布高度
     * @param autoExpand 是否自动扩展画布高度
     * @return 绘图器
     */
    public CommonPainter create(int width, int height, boolean autoExpand) {
        return new CommonPainter(buildProperties, properties, fontService, width, height, autoExpand);
    }
}
