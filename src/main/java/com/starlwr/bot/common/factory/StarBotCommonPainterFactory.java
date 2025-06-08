package com.starlwr.bot.common.factory;

import com.starlwr.bot.common.painter.CommonPainter;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * StarBot 绘图器工厂
 */
@Component
public class StarBotCommonPainterFactory {
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 创建绘图器
     * @param width 画布宽度
     * @param height 画布高度
     * @return 绘图器
     */
    public CommonPainter create(int width, int height) {
        return applicationContext.getBean(CommonPainter.class, width, height, false);
    }

    /**
     * 创建绘图器
     * @param width 画布宽度
     * @param height 画布高度
     * @param autoExpand 是否自动扩展画布高度
     * @return 绘图器
     */
    public CommonPainter create(int width, int height, boolean autoExpand) {
        return applicationContext.getBean(CommonPainter.class, width, height, autoExpand);
    }
}
