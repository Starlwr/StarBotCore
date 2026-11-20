package com.starlwr.bot.core.config;

import ch.qos.logback.classic.Level;
import com.starlwr.bot.core.ai.AiModel;
import com.starlwr.bot.core.command.CommandPermission;
import com.starlwr.bot.core.message.Sender;
import com.starlwr.bot.core.painter.TextWithStyle;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * StarBotCore 配置类
 */
@Getter
@Setter
@StarBotConfiguration(prefix = "starbot.core")
public class StarBotCoreProperties {
    @Getter
    private final ConfigCenter configCenter = new ConfigCenter();

    @Getter
    private final Log log = new Log();

    @Getter
    private final Debug debug = new Debug();

    @Getter
    private final Network network = new Network();

    @Getter
    private final NetworkThread networkThread = new NetworkThread();

    @Getter
    private final CommandThread commandThread = new CommandThread();

    @Getter
    private final AiThread aiThread = new AiThread();

    @Getter
    private final Plugin plugin = new Plugin();

    @Getter
    private final Paint paint = new Paint();

    @Getter
    private final Mail mail = new Mail();

    @Getter
    private final Ai ai = new Ai();

    @Getter
    private final Command command = new Command();

    @Getter
    private final DataSource datasource = new DataSource();

    @Getter
    private final Live live = new Live();

    /**
     * 非插件实现的推送平台配置列表
     */
    @Getter
    private final List<Sender> sender = new ArrayList<>();

    /**
     * 配置中心相关
     */
    @Getter
    @Setter
    public static class ConfigCenter {
        /**
         * 是否启用配置中心
         */
        private boolean enabled = true;

        /**
         * 访问密码，留空时启动后自动生成随机密码
         */
        private String password = "";
    }

    /**
     * 日志相关
     */
    @Getter
    @Setter
    public static class Log {
        /**
         * 控制台日志级别
         */
        private Level console = Level.INFO;

        /**
         * 文件日志级别
         */
        private Level file = Level.INFO;
    }

    /**
     * 调试相关
     */
    @Getter
    @Setter
    public static class Debug {
        /**
         * 是否记录事件日志
         */
        private boolean eventDebug = true;

        /**
         * 是否记录网络请求日志
         */
        private boolean networkDebug = true;
    }

    /**
     * 网络相关
     */
    @Getter
    @Setter
    public static class Network {
        /**
         * 网络请求连接超时时间，单位：秒
         */
        private int connectTimeout = 10;

        /**
         * 网络请求读取超时时间，单位：秒
         */
        private int readTimeout = 60;
    }

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
     * 命令线程相关
     */
    @Getter
    @Setter
    public static class CommandThread {
        /**
         * 线程池核心线程数
         */
        private int corePoolSize = 10;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 10;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = Integer.MAX_VALUE;

        /**
         * 非核心线程存活时间，单位：秒
         */
        private int keepAliveSeconds = 60;
    }

    /**
     * AI 线程相关
     */
    @Getter
    @Setter
    public static class AiThread {
        /**
         * 线程池核心线程数
         */
        private int corePoolSize = 5;

        /**
         * 线程池最大线程数
         */
        private int maxPoolSize = 30;

        /**
         * 线程池任务队列容量
         */
        private int queueCapacity = 10;

        /**
         * 非核心线程存活时间，单位：秒
         */
        private int keepAliveSeconds = 60;
    }

    /**
     * 插件相关
     */
    @Getter
    @Setter
    public static class Plugin {
        /**
         * 是否自动下载插件依赖，可使用 --skip-download-dependency 命令行参数临时跳过自动下载
         */
        private boolean autoDownloadDependency = true;

        /**
         * 下载插件依赖时使用的 Maven 仓库列表
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
         * 是否绘制 StarBot Logo
         */
        private boolean drawLogo = true;

        /**
         * 自定义字体列表，支持配置为字体名称或字体文件路径
         */
        private List<String> fonts = new ArrayList<>();

        /**
         * 绘图器自动扩展高度时扩展像素数，设置过大会导致产生较大的峰值内存占用，设置过小会频繁自动扩展导致效率降低
         */
        private int autoExpandHeight = 5000;

        /**
         * 自定义绘图器底部额外版权信息
         */
        private List<TextWithStyle> extraCopyrights = new ArrayList<>();

    }

    /**
     * 邮件相关
     */
    @Getter
    @Setter
    public static class Mail {
        /**
         * 是否启用邮件
         */
        private boolean enabled = false;

        /**
         * 默认收件邮箱
         */
        private String defaultTo;
    }

    /**
     * AI 相关
     */
    @Getter
    @Setter
    public static class Ai {
        /**
         * 默认模型名称，配置多个模型时必须指定
         */
        private String defaultModel;

        /**
         * AI 模型配置映射
         */
        private Map<String, AiModel> models = new LinkedHashMap<>();
    }

    /**
     * 命令相关
     */
    @Getter
    @Setter
    public static class Command {
        /**
         * 全局命令前缀，空字符串表示无需前缀
         */
        private String prefix = "";

        /**
         * 群聊触发命令是否必须提及机器人，@ 机器人或回复机器人的消息均视为提及，私聊不受限制
         */
        private boolean mention = false;

        /**
         * 全局禁用的命令 ID 列表
         */
        private List<String> disabled = new ArrayList<>();

        /**
         * 按命令 ID 覆盖指定命令的权限，未配置时使用命令默认权限
         */
        private Map<String, CommandPermission> permissions = new HashMap<>();

        /**
         * 主人账号，按推送平台名称分别配置
         */
        private Map<String, List<String>> owners = new HashMap<>();

        /**
         * 命令禁用配置文件路径
         */
        private String disabledCommandPath = "data/disabled-command.json";
    }

    /**
     * 数据源相关
     */
    @Getter
    @Setter
    public static class DataSource {
        /**
         * 数据源类型
         */
        private String type = "json";

        /**
         * JSON 文件路径，仅使用 JSON 数据源时生效
         */
        private String jsonPath = "datasource.json";

        /**
         * JSON 文件发生变化时是否自动重载，仅使用 JSON 数据源时生效
         */
        private boolean jsonAutoReload = true;
    }

    /**
     * 直播相关
     */
    @Getter
    @Setter
    public static class Live {
        /**
         * 是否保存直播数据至文件，仅使用默认直播数据服务时生效
         */
        private boolean saveLiveData = true;

        /**
         * 直播数据文件路径，仅使用默认直播数据服务时生效
         */
        private String liveDataPath = "data/data.json";

        /**
         * 自动保存直播数据间隔，单位：秒，仅使用默认直播数据服务时生效
         */
        private int autoSaveLiveDataInterval = 300;

        /**
         * 判定主播断线重连（下播后短时间内重新开播）的时间间隔，断线重连不会重置直播数据，单位：秒
         */
        private int reconnectInterval = 300;
    }
}
