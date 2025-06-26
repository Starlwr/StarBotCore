package com.starlwr.bot.core.config;

import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.model.TextWithStyle;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * StarBotCore 配置类
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "starbot.core")
public class StarBotCoreProperties {
    @Getter
    private final NetworkThread networkThread = new NetworkThread();

    @Getter
    private final EventHandlerThread eventHandlerThread = new EventHandlerThread();

    @Getter
    private final DataSource datasource = new DataSource();

    @Getter
    private final Plugin plugin = new Plugin();

    @Getter
    private final Paint paint = new Paint();

    @Getter
    private final Map<String, Sender> sender = new HashMap<>();

    /**
     * 网络线程相关
     */
    @Getter
    @Setter
    public static class NetworkThread {
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
     * 事件处理线程相关
     */
    @Getter
    @Setter
    public static class EventHandlerThread {
        /**
         * 线程池核心线程数
         */
        private int corePoolSize = 10;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 100;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 0;

        /**
         * 非核心线程存活时间，单位：秒
         */
        private int keepAliveSeconds = 300;
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
     * 数据源相关
     */
    @Getter
    @Setter
    public static class Plugin {
        /**
         * 是否自动下载插件依赖，可使用 --skip-download-dependency 命令行参数临时跳过自动下载
         */
        private boolean autoDownloadDependency = true;

        /**
         * 用于自动下载插件依赖的 Maven 地址
         */
        private List<String> mavenBaseUrls = new ArrayList<>(Arrays.asList("https://maven.aliyun.com/repository/public", "https://repo1.maven.org/maven2"));
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
        private List<String> fonts = new ArrayList<>();

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
        String os = System.getProperty("os.name").toLowerCase();
        paint.getFonts().add("内置");
        if (os.contains("win")) {
            paint.getFonts().addAll(Arrays.asList("微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial", "SansSerif"));
        } else if (os.contains("mac")) {
            paint.getFonts().addAll(Arrays.asList("PingFang SC", "Apple Color Emoji", "SansSerif"));
        } else {
            // sudo apt install -y  fonts-noto-cjk  fonts-wqy-zenhei  fonts-noto-color-emoji fonts-freefont-ttf
            paint.getFonts().addAll(Arrays.asList("Noto Sans CJK SC", "WenQuanYi Zen Hei", "Noto Color Emoji", "DejaVu Sans", "FreeSans", "SansSerif"));
        }

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
