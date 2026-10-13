package com.starlwr.bot.core.support.system;

import com.starlwr.bot.core.StarBotCoreApplication;
import com.starlwr.bot.core.support.system.SystemResourceSnapshot.Usage;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.management.OperatingSystemMXBean;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import oshi.util.PlatformEnum;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 系统资源查询服务
 */
@Slf4j
@Service
public class SystemResourceService {
    private final SystemInfo systemInfo;

    private final OperatingSystemMXBean operatingSystem;

    private final Path starBotDirectory;

    /**
     * Windows 原生进程内存结构
     */
    @Structure.FieldOrder({"cb", "pageFaultCount", "peakWorkingSetSize", "workingSetSize", "quotaPeakPagedPoolUsage", "quotaPagedPoolUsage", "quotaPeakNonPagedPoolUsage", "quotaNonPagedPoolUsage", "pagefileUsage", "peakPagefileUsage"})
    public static class WindowsMemoryCounters extends Structure {
        /**
         * 结构体大小，单位：字节
         */
        public int cb;

        /**
         * 缺页次数
         */
        public int pageFaultCount;

        /**
         * 物理工作集峰值，单位：字节
         */
        public SIZE_T peakWorkingSetSize;

        /**
         * 当前物理工作集大小，单位：字节
         */
        public SIZE_T workingSetSize;

        /**
         * 分页池配额使用峰值，单位：字节
         */
        public SIZE_T quotaPeakPagedPoolUsage;

        /**
         * 当前分页池配额使用量，单位：字节
         */
        public SIZE_T quotaPagedPoolUsage;

        /**
         * 非分页池配额使用峰值，单位：字节
         */
        public SIZE_T quotaPeakNonPagedPoolUsage;

        /**
         * 当前非分页池配额使用量，单位：字节
         */
        public SIZE_T quotaNonPagedPoolUsage;

        /**
         * 当前进程提交内存大小，单位：字节
         */
        public SIZE_T pagefileUsage;

        /**
         * 进程提交内存峰值，单位：字节
         */
        public SIZE_T peakPagefileUsage;
    }

    /**
     * Windows 进程状态查询接口
     */
    private interface WindowsProcessApi extends StdCallLibrary {
        WindowsProcessApi INSTANCE = Native.load("psapi", WindowsProcessApi.class);

        /**
         * 读取指定进程的内存计数器
         * @param process 进程句柄
         * @param counters 接收进程内存信息的结构体
         * @param size 结构体大小，单位为字节
         * @return 查询成功时返回 true
         */
        boolean GetProcessMemoryInfo(HANDLE process, WindowsMemoryCounters counters, int size);
    }

    public SystemResourceService() {
        this(new SystemInfo(), (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean(), new ApplicationHome(StarBotCoreApplication.class).getDir().toPath());
    }

    private SystemResourceService(SystemInfo systemInfo, OperatingSystemMXBean operatingSystem, Path starBotDirectory) {
        this.systemInfo = systemInfo;
        this.operatingSystem = operatingSystem;
        this.starBotDirectory = starBotDirectory.toAbsolutePath().normalize();
    }

    /**
     * 获取当前系统资源快照
     * @return 当前系统资源快照
     */
    public SystemResourceSnapshot getSnapshot() {
        long memoryTotal = getMemoryTotalBytes();
        long memoryUsed = usedBytes(memoryTotal, getMemoryAvailableBytes());
        long starBotMemory = getStarBotMemoryBytes();
        long diskTotal = getDiskTotalBytes();
        long diskUsed = usedBytes(diskTotal, getDiskFreeBytes());
        long starBotDisk = getStarBotDiskBytes();
        return SystemResourceSnapshot.builder()
                .operatingSystem(getOperatingSystem())
                .memory(new Usage(ratio(memoryUsed, memoryTotal), ratio(starBotMemory, memoryTotal)))
                .cpu(new Usage(getCpuUsage(), getStarBotCpuUsage()))
                .disk(new Usage(ratio(diskUsed, diskTotal), ratio(starBotDisk, diskTotal)))
                .memoryTotalBytes(memoryTotal).memoryUsedBytes(memoryUsed).starBotMemoryBytes(starBotMemory)
                .physicalProcessorCount(getPhysicalProcessorCount()).logicalProcessorCount(getLogicalProcessorCount())
                .diskTotalBytes(diskTotal).diskUsedBytes(diskUsed).starBotDiskBytes(starBotDisk)
                .build();
    }

    /**
     * 获取操作系统信息
     * @return 操作系统信息，获取失败时返回系统信息获取失败
     */
    public String getOperatingSystem() {
        return read("操作系统", () -> {
            OperatingSystem os = systemInfo.getOperatingSystem();
            String family = os.getFamily();
            String version = os.getVersionInfo().getVersion();
            String name = StringUtils.defaultIfBlank(family, System.getProperty("os.name"));
            if (StringUtils.isNotBlank(version)) {
                name += " " + version;
            }
            return os.getBitness() > 0 ? name + " · " + os.getBitness() + " 位" : name;
        }, "系统信息获取失败");
    }

    /**
     * 获取系统物理内存总字节数
     * @return 系统物理内存总字节数，获取失败时返回 -1
     */
    public long getMemoryTotalBytes() {
        return read("总内存", () -> positiveBytes(systemInfo.getHardware().getMemory().getTotal()), -1L);
    }

    /**
     * 获取系统可用物理内存字节数
     * @return 系统可用物理内存字节数，获取失败时返回 -1
     */
    public long getMemoryAvailableBytes() {
        return read("可用内存", () -> validBytes(systemInfo.getHardware().getMemory().getAvailable()), -1L);
    }

    /**
     * 获取 StarBot 进程的物理驻留内存字节数
     * @return StarBot 进程的物理驻留内存字节数，获取失败时返回 -1
     */
    public long getStarBotMemoryBytes() {
        return read("StarBot 内存", () -> {
            if (PlatformEnum.getCurrentPlatform() == PlatformEnum.WINDOWS) {
                WindowsMemoryCounters counters = new WindowsMemoryCounters();
                counters.cb = counters.size();
                return WindowsProcessApi.INSTANCE.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(), counters, counters.size()) ? validBytes(counters.workingSetSize.longValue()) : -1L;
            }
            OSProcess process = systemInfo.getOperatingSystem().getProcess(Math.toIntExact(ProcessHandle.current().pid()));
            return process == null ? -1L : validBytes(process.getResidentMemory());
        }, -1L);
    }

    /**
     * 获取近期整机 CPU 使用率
     * @return 近期整机 CPU 使用率，获取失败时返回负数或 NaN
     */
    public double getCpuUsage() {
        return read("CPU 使用率", operatingSystem::getCpuLoad, Double.NaN);
    }

    /**
     * 获取 StarBot 进程的近期 CPU 使用率
     * @return StarBot 进程的近期 CPU 使用率，获取失败时返回负数或 NaN
     */
    public double getStarBotCpuUsage() {
        return read("StarBot CPU 使用率", operatingSystem::getProcessCpuLoad, Double.NaN);
    }

    /**
     * 获取 CPU 物理核心数
     * @return CPU 物理核心数，获取失败时返回 0
     */
    public int getPhysicalProcessorCount() {
        return read("物理核心数", () -> Math.max(0, systemInfo.getHardware().getProcessor().getPhysicalProcessorCount()), 0);
    }

    /**
     * 获取 CPU 逻辑处理器数
     * @return CPU 逻辑处理器数，获取失败时返回 0
     */
    public int getLogicalProcessorCount() {
        return read("逻辑处理器数", () -> Math.max(0, systemInfo.getHardware().getProcessor().getLogicalProcessorCount()), 0);
    }

    /**
     * 获取主程序所在文件系统的总字节数
     * @return 主程序所在文件系统的总字节数，获取失败时返回 -1
     */
    public long getDiskTotalBytes() {
        return read("磁盘总容量", () -> positiveBytes(Files.getFileStore(starBotDirectory).getTotalSpace()), -1L);
    }

    /**
     * 获取主程序所在文件系统的未分配空间字节数
     * @return 主程序所在文件系统的未分配空间字节数，获取失败时返回 -1
     */
    public long getDiskFreeBytes() {
        return read("磁盘剩余容量", () -> validBytes(Files.getFileStore(starBotDirectory).getUnallocatedSpace()), -1L);
    }

    /**
     * 获取主程序所在目录及子目录内普通文件的逻辑大小
     * @return 主程序所在目录及子目录内普通文件的逻辑大小，无法完整读取目录时返回 -1
     */
    public long getStarBotDiskBytes() {
        return read("StarBot 目录容量", () -> {
            Path directory = starBotDirectory.toRealPath();
            FileStore store = Files.getFileStore(directory);
            Set<Object> countedFiles = new HashSet<>();
            Map<FileTime, List<Path>> filesWithoutKeys = new HashMap<>();
            long[] total = {0};

            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                /**
                 * 检查目录是否位于当前文件系统内，并跳过目录联接
                 * @param dir 待访问的目录
                 * @param attributes 目录属性
                 * @return 继续访问或跳过当前子目录
                 */
                @Override
                public @NonNull FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attributes) throws IOException {
                    return dir.toRealPath().equals(dir) && Files.getFileStore(dir).equals(store) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                /**
                 * 累加尚未统计的普通文件大小
                 * @param file 当前文件
                 * @param attributes 文件属性
                 * @return 继续遍历文件
                 */
                @Override
                public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile() && attributes.size() > 0 && !alreadyCounted(file, attributes)) {
                        total[0] = Math.addExact(total[0], attributes.size());
                    }
                    return FileVisitResult.CONTINUE;
                }

                /**
                 * 忽略查询期间删除的文件，其他访问失败继续抛出异常
                 * @param file 访问失败的文件
                 * @param exception 文件访问异常
                 * @return 文件已经删除时继续遍历
                 */
                @Override
                public @NonNull FileVisitResult visitFileFailed(@NonNull Path file, @NonNull IOException exception) throws IOException {
                    if (exception instanceof NoSuchFileException) {
                        return FileVisitResult.CONTINUE;
                    }
                    throw exception;
                }

                /**
                 * 根据文件标识或硬链接身份判断文件是否已经统计
                 * @param file 当前文件
                 * @param attributes 文件属性
                 * @return 当前文件已统计时返回 true
                 */
                private boolean alreadyCounted(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.fileKey() != null) {
                        return !countedFiles.add(attributes.fileKey());
                    }

                    List<Path> candidates = filesWithoutKeys.computeIfAbsent(attributes.creationTime(), key -> new ArrayList<>());
                    for (Path candidate : candidates) {
                        try {
                            if (Files.isSameFile(file, candidate)) {
                                return true;
                            }
                        } catch (NoSuchFileException ignored) {
                        }
                    }
                    candidates.add(file);
                    return false;
                }
            });

            return total[0];
        }, -1L);
    }

    /**
     * 检查必须大于等于零的资源总容量
     * @param bytes 待检查的字节数
     * @return 非负字节数，非法值返回 -1
     */
    private static long validBytes(long bytes) {
        return bytes >= 0 ? bytes : -1;
    }

    /**
     * 检查必须大于零的资源总容量
     * @param bytes 待检查的字节数
     * @return 正数字节数，非法值返回 -1
     */
    private static long positiveBytes(long bytes) {
        return bytes > 0 ? bytes : -1;
    }

    /**
     * 根据总容量和剩余容量计算已用容量
     * @param total 总字节数
     * @param free 剩余字节数
     * @return 已用字节数，容量关系非法时返回 -1
     */
    private static long usedBytes(long total, long free) {
        return total > 0 && free >= 0 && free <= total ? total - free : -1;
    }

    /**
     * 将资源使用量换算为占用率
     * @param used 已用字节数
     * @param total 总字节数
     * @return 占用率，任一容量获取失败时返回 NaN
     */
    private static double ratio(long used, long total) {
        return used >= 0 && total > 0 ? used / (double) total : Double.NaN;
    }

    /**
     * 查询单项资源
     * @param resource 资源名称
     * @param reader 资源查询操作
     * @param unavailable 查询失败时的返回值
     * @return 查询结果或查询失败时的返回值
     */
    private <T> T read(String resource, Callable<T> reader, T unavailable) {
        try {
            return reader.call();
        } catch (Exception | LinkageError e) {
            log.warn("获取 {} 失败", resource, e);
            return unavailable;
        }
    }
}
