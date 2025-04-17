package com.starlwr.bot.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
}
