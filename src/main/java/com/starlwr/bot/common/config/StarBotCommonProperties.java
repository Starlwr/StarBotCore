package com.starlwr.bot.common.config;

import com.starlwr.bot.common.model.TextWithStyle;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * StarBotCommon 配置类
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "starbot.common")
public class StarBotCommonProperties {
    @Getter
    private final Version version = new Version();

    @Getter
    private final Thread thread = new Thread();

    @Getter
    private final DataSource datasource = new DataSource();

    @Getter
    private final Paint paint = new Paint();

    /**
     * 版本相关
     */
    @Getter
    @Setter
    public static class Version {
        /**
         * 版本号
         */
        private String number;

        /**
         * 发布日期
         */
        private String releaseDate;
    }

    /**
     * 线程相关
     */
    @Getter
    @Setter
    public static class Thread {
        /**
         * 线程池核心线程数
         */
        private int corePoolSize = 10;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 50;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 5;

        /**
         * 非核心线程存活时间，单位：秒
         */
        private int keepAliveSeconds = 60;
    }

    /**
     * 数据源相关
     */
    @Getter
    @Setter
    public static class DataSource {
        /**
         * JSON 文件路径，仅使用 JSON 数据源时生效
         */
        private String jsonPath = "datasource.json";

        /**
         * JSON 文件发生变化时是否自动重载
         */
        private boolean jsonAutoReload = true;
    }

    /**
     * 绘图相关
     */
    @Getter
    @Setter
    public static class Paint {
        /**
         * 绘图器字体列表，支持配置为字体名称或字体文件路径
         */
        private List<String> fonts = new ArrayList<>(Arrays.asList("内置", "宋体", "微软雅黑", "Segoe UI Symbol", "Segoe UI Emoji"));

        /**
         * 绘图器自动扩展高度时扩展像素数，设置过大会导致占用较大内存，设置过小会频繁自动扩展导致效率降低
         */
        private int autoExpandHeight = 5000;

        /**
         * 自定义绘图器底部额外版权信息
         */
        private List<TextWithStyle> extraCopyrights = new ArrayList<>();
    }

    @PostConstruct
    public void init() {
        for (TextWithStyle extra : paint.getExtraCopyrights()) {
            if (extra.getFont() != null) {
                if (extra.getSize() != 0) {
                    extra.setFont(extra.getFont().deriveFont(extra.getStyle(), extra.getSize()));
                } else {
                    extra.setFont(extra.getFont().deriveFont(extra.getStyle()));
                }
            }
        }
    }
}
