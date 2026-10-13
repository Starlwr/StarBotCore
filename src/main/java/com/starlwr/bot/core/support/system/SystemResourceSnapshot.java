package com.starlwr.bot.core.support.system;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 系统资源快照
 */
@Getter
@Builder
@ToString
public class SystemResourceSnapshot {
    /**
     * 单项资源占用率，StarBot 占用包含在总占用中
     */
    @Getter
    @ToString
    public static class Usage {
        /**
         * 系统总占用率，范围为 0 到 1，获取失败时为 NaN
         */
        private final double system;

        /**
         * StarBot 占用率，不超过系统总占用率，获取失败时为 NaN
         */
        private final double starBot;

        /**
         * 创建资源占用率，并限制 StarBot 占用不超过系统总占用
         * @param system 系统总占用率
         * @param starBot StarBot 占用率
         */
        public Usage(double system, double starBot) {
            this.system = normalize(system);
            double normalizedStarBot = normalize(starBot);
            this.starBot = Double.isFinite(this.system) && Double.isFinite(normalizedStarBot) ? Math.min(normalizedStarBot, this.system) : normalizedStarBot;
        }

        /**
         * 创建获取失败的资源占用率
         * @return 获取失败的资源占用率
         */
        private static Usage unavailable() {
            return new Usage(Double.NaN, Double.NaN);
        }

        /**
         * 判断系统和 StarBot 占用率是否均可用
         * @return 系统和 StarBot 占用率是否均可用
         */
        public boolean isAvailable() {
            return Double.isFinite(system) && Double.isFinite(starBot);
        }

        /**
         * 获取除 StarBot 外的资源占用率
         * @return 除 StarBot 外的资源占用率
         */
        public double getOther() {
            return system - starBot;
        }

        /**
         * 将合法占用率限制在 0 到 1，非法值转换为获取失败标记
         * @param value 占用率
         * @return 归一化占用率
         */
        private static double normalize(double value) {
            return Double.isFinite(value) && value >= 0 ? Math.min(value, 1) : Double.NaN;
        }
    }

    /**
     * 操作系统信息
     */
    @Builder.Default
    private final String operatingSystem = "系统信息获取失败";

    /**
     * 内存占用率
     */
    @Builder.Default
    private final Usage memory = Usage.unavailable();

    /**
     * CPU 使用率
     */
    @Builder.Default
    private final Usage cpu = Usage.unavailable();

    /**
     * 磁盘容量占用率
     */
    @Builder.Default
    private final Usage disk = Usage.unavailable();

    /**
     * 系统物理内存总字节数，获取失败时为 -1
     */
    @Builder.Default
    private final long memoryTotalBytes = -1;

    /**
     * 系统已用物理内存字节数，获取失败时为 -1
     */
    @Builder.Default
    private final long memoryUsedBytes = -1;

    /**
     * StarBot 进程物理驻留内存字节数，获取失败时为 -1
     */
    @Builder.Default
    private final long starBotMemoryBytes = -1;

    /**
     * CPU 物理核心数，获取失败时为 0
     */
    private final int physicalProcessorCount;

    /**
     * CPU 逻辑处理器数，获取失败时为 0
     */
    private final int logicalProcessorCount;

    /**
     * 主程序所在文件系统的总字节数，获取失败时为 -1
     */
    @Builder.Default
    private final long diskTotalBytes = -1;

    /**
     * 主程序所在文件系统的已用字节数，获取失败时为 -1
     */
    @Builder.Default
    private final long diskUsedBytes = -1;

    /**
     * 主程序所在目录及子目录内普通文件的逻辑大小合计，获取失败时为 -1
     */
    @Builder.Default
    private final long starBotDiskBytes = -1;
}
