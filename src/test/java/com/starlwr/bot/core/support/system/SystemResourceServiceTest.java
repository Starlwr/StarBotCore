package com.starlwr.bot.core.support.system;

import com.starlwr.bot.core.support.system.SystemResourceSnapshot.Usage;
import com.sun.jna.Native;
import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OperatingSystem.OSVersionInfo;

import java.lang.reflect.Constructor;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 系统资源查询服务测试
 * <p>
 * 覆盖按需读取、单项查询失败、CPU 占用率换算及主 jar 目录的磁盘统计范围。
 */
class SystemResourceServiceTest {
    /**
     * 模拟主 jar 所在目录
     */
    @TempDir
    private Path directory;

    /**
     * 模拟系统信息查询入口
     */
    private SystemInfo systemInfo;

    /**
     * 模拟物理内存信息
     */
    private GlobalMemory memory;

    /**
     * 模拟 CPU 核心信息
     */
    private CentralProcessor processor;

    /**
     * 模拟 CPU 使用率查询接口
     */
    private OperatingSystemMXBean operatingSystem;

    /**
     * 被测系统资源查询服务
     */
    private SystemResourceService service;

    /**
     * 准备资源查询依赖和测试文件，通过反射构造服务实例
     * @throws Exception 反射构造服务或准备测试文件失败时抛出
     */
    @BeforeEach
    void setup() throws Exception {
        systemInfo = mock(SystemInfo.class);
        HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
        processor = mock(CentralProcessor.class);
        memory = mock(GlobalMemory.class);
        OperatingSystem os = mock(OperatingSystem.class);
        OSVersionInfo version = mock(OSVersionInfo.class);
        operatingSystem = mock(OperatingSystemMXBean.class);
        when(systemInfo.getHardware()).thenReturn(hardware);
        when(systemInfo.getOperatingSystem()).thenReturn(os);
        when(hardware.getProcessor()).thenReturn(processor);
        when(hardware.getMemory()).thenReturn(memory);
        when(memory.getTotal()).thenReturn(1000L);
        when(memory.getAvailable()).thenReturn(520L);
        when(processor.getPhysicalProcessorCount()).thenReturn(4);
        when(processor.getLogicalProcessorCount()).thenReturn(8);
        when(os.getFamily()).thenReturn("Windows");
        when(os.getVersionInfo()).thenReturn(version);
        when(version.getVersion()).thenReturn("11");
        when(os.getBitness()).thenReturn(64);
        when(operatingSystem.getCpuLoad()).thenReturn(0.12);
        when(operatingSystem.getProcessCpuLoad()).thenReturn(0.03);
        service = spy(createService(directory));
        doReturn(80L).when(service).getStarBotMemoryBytes();
        doReturn(1000L).when(service).getDiskTotalBytes();
        doReturn(360L).when(service).getDiskFreeBytes();
        Files.write(directory.resolve("StarBotCore.jar"), new byte[20]);
    }

    /**
     * 验证构造服务时不会提前查询系统资源
     */
    @Test
    void doesNotReadResourcesBeforeRequested() {
        verifyNoInteractions(systemInfo, operatingSystem);
    }

    /**
     * 仅在 Windows 上验证原生内存结构大小与 PROCESS_MEMORY_COUNTERS 布局一致
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void preservesWindowsNativeMemoryLayout() {
        assertEquals(8 + Native.SIZE_T_SIZE * 8, new SystemResourceService.WindowsMemoryCounters().size());
    }

    /**
     * 验证首次查询即可返回各项资源，无需提前建立 CPU 采样基线
     */
    @Test
    void readsAllResourcesOnFirstRequestWithoutCpuBaseline() {
        var result = service.getSnapshot();
        assertEquals("Windows 11 · 64 位", result.getOperatingSystem());
        assertEquals(0.48, result.getMemory().getSystem(), 0.000001);
        assertEquals(0.08, result.getMemory().getStarBot(), 0.000001);
        assertEquals(1000, result.getMemoryTotalBytes());
        assertEquals(480, result.getMemoryUsedBytes());
        assertEquals(80, result.getStarBotMemoryBytes());
        assertEquals(4, result.getPhysicalProcessorCount());
        assertEquals(8, result.getLogicalProcessorCount());
        assertEquals(0.12, result.getCpu().getSystem(), 0.000001);
        assertEquals(0.03, result.getCpu().getStarBot(), 0.000001);
        assertEquals(0.09, result.getCpu().getOther(), 0.000001);
        assertEquals(1000, result.getDiskTotalBytes());
        assertEquals(640, result.getDiskUsedBytes());
        assertEquals(20, result.getStarBotDiskBytes());
        assertEquals(0.64, result.getDisk().getSystem(), 0.000001);
        assertEquals(0.02, result.getDisk().getStarBot(), 0.000001);
        verify(operatingSystem).getCpuLoad();
        verify(operatingSystem).getProcessCpuLoad();
    }

    /**
     * 验证每次请求都会重新读取内存、CPU 和应用目录文件大小
     * @throws Exception 更新测试文件失败时抛出
     */
    @Test
    void rereadsMemoryCpuAndDirectoryOnEveryRequest() throws Exception {
        var first = service.getSnapshot();
        when(memory.getAvailable()).thenReturn(400L);
        when(operatingSystem.getCpuLoad()).thenReturn(0.5);
        when(operatingSystem.getProcessCpuLoad()).thenReturn(0.2);
        Files.write(directory.resolve("StarBotCore.jar"), new byte[60]);
        var second = service.getSnapshot();
        assertEquals(480, first.getMemoryUsedBytes());
        assertEquals(600, second.getMemoryUsedBytes());
        assertEquals(0.5, second.getCpu().getSystem());
        assertEquals(0.2, second.getCpu().getStarBot());
        assertEquals(20, first.getStarBotDiskBytes());
        assertEquals(60, second.getStarBotDiskBytes());
    }

    /**
     * 验证磁盘统计包含应用目录及子目录，切换根目录后仅统计新的目录范围
     * @throws Exception 创建测试文件或反射构造服务失败时抛出
     */
    @Test
    void countsOnlyApplicationDirectoryAndItsChildren() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        Path data = Files.createDirectories(directory.resolve("data"));
        Files.write(plugins.resolve("plugin.jar"), new byte[30]);
        Files.write(data.resolve("database.json"), new byte[50]);
        assertEquals(100, service.getStarBotDiskBytes());

        SystemResourceService pluginDirectory = createService(plugins);
        assertEquals(30, pluginDirectory.getStarBotDiskBytes());
    }

    /**
     * 验证同一文件的多个硬链接只统计一次，文件系统不支持或不允许创建硬链接时跳过
     * @throws Exception 创建硬链接发生其他 IO 异常时抛出
     */
    @Test
    void countsHardLinkedFileOnlyOnce() throws Exception {
        try {
            Files.createLink(directory.resolve("same-jar.jar"), directory.resolve("StarBotCore.jar"));
        } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            assumeTrue(false, "当前测试环境无法创建硬链接: " + e.getMessage());
        }
        assertEquals(20, service.getStarBotDiskBytes());
    }

    /**
     * 验证空目录和删除目录不会继续使用之前的容量查询结果
     * @throws Exception 删除测试文件或反射构造服务失败时抛出
     */
    @Test
    void handlesEmptyAndRemovedDirectoriesWithoutCachedSizes() throws Exception {
        assertEquals(20, service.getStarBotDiskBytes());
        Files.delete(directory.resolve("StarBotCore.jar"));
        assertEquals(0, service.getStarBotDiskBytes());
        SystemResourceService missing = spy(createService(directory.resolve("missing")));
        doReturn(80L).when(missing).getStarBotMemoryBytes();
        var result = missing.getSnapshot();
        assertEquals(-1, result.getDiskTotalBytes());
        assertEquals(-1, result.getStarBotDiskBytes());
        assertFalse(result.getDisk().isAvailable());
        assertTrue(result.getMemory().isAvailable());
        assertTrue(result.getCpu().isAvailable());
    }

    /**
     * 使用模拟文件系统验证磁盘总容量和剩余容量的查询，不依赖测试机器的磁盘读数
     * @throws Exception 配置文件系统查询或反射构造服务失败时抛出
     */
    @Test
    void queriesCapacityOfApplicationFileSystem() throws Exception {
        var actual = createService(directory);
        FileStore store = mock(FileStore.class);
        when(store.getTotalSpace()).thenReturn(1000L);
        when(store.getUnallocatedSpace()).thenReturn(360L);
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.getFileStore(directory.toAbsolutePath().normalize())).thenReturn(store);
            assertEquals(1000, actual.getDiskTotalBytes());
            assertEquals(360, actual.getDiskFreeBytes());
        }
    }

    /**
     * 验证内存查询失败不影响 CPU 和磁盘，且后续请求可以恢复
     */
    @Test
    void keepsCpuAndDiskWhenNativeMemoryQueryFailsAndRecoversNextRequest() {
        when(memory.getTotal()).thenThrow(new UnsatisfiedLinkError("测试内存接口失败"));
        var failed = service.getSnapshot();
        assertEquals(-1, failed.getMemoryTotalBytes());
        assertFalse(failed.getMemory().isAvailable());
        assertTrue(failed.getCpu().isAvailable());
        assertTrue(failed.getDisk().isAvailable());
        doReturn(1000L).when(memory).getTotal();
        assertTrue(service.getSnapshot().getMemory().isAvailable());
    }

    /**
     * 验证获取失败读数不会显示为零，CPU 使用率不会再次除以核心数
     */
    @Test
    void preservesUnknownCountersAndDoesNotDivideJdkProcessLoadAgain() {
        when(operatingSystem.getCpuLoad()).thenReturn(-1.0);
        when(operatingSystem.getProcessCpuLoad()).thenReturn(-1.0);
        doReturn(-1L).when(service).getStarBotMemoryBytes();
        var result = service.getSnapshot();
        assertEquals(0.48, result.getMemory().getSystem());
        assertTrue(Double.isNaN(result.getMemory().getStarBot()));
        assertFalse(result.getCpu().isAvailable());

        when(operatingSystem.getCpuLoad()).thenReturn(0.6);
        when(operatingSystem.getProcessCpuLoad()).thenReturn(0.5);
        assertEquals(0.5, service.getSnapshot().getCpu().getStarBot());
    }

    /**
     * 验证非法容量关系和负核心数会转换为获取失败标记
     */
    @Test
    void rejectsInvalidCapacitiesAndProcessorCounts() {
        when(memory.getAvailable()).thenReturn(1200L);
        when(processor.getPhysicalProcessorCount()).thenReturn(-1);
        when(processor.getLogicalProcessorCount()).thenReturn(-1);
        doReturn(1200L).when(service).getDiskFreeBytes();
        var result = service.getSnapshot();
        assertEquals(-1, result.getMemoryUsedBytes());
        assertTrue(Double.isNaN(result.getMemory().getSystem()));
        assertEquals(-1, result.getDiskUsedBytes());
        assertTrue(Double.isNaN(result.getDisk().getSystem()));
        assertEquals(0, result.getPhysicalProcessorCount());
        assertEquals(0, result.getLogicalProcessorCount());
    }

    /**
     * 验证进程 CPU 查询失败不会影响整机 CPU、内存和磁盘查询
     */
    @Test
    void handlesUnavailableProcessCpuIndependently() {
        when(operatingSystem.getProcessCpuLoad()).thenThrow(new UnsatisfiedLinkError("测试进程 CPU 失败"));
        var result = service.getSnapshot();
        assertEquals(0.12, result.getCpu().getSystem());
        assertTrue(Double.isNaN(result.getCpu().getStarBot()));
        assertTrue(result.getMemory().isAvailable());
        assertTrue(result.getDisk().isAvailable());
    }

    /**
     * 验证 StarBot 占用不超过系统总占用，非法占用率会转换为获取失败标记
     */
    @Test
    void keepsStarBotWithinTotalAndRejectsInvalidUsage() {
        var usage = new Usage(0.1, 0.2);
        assertEquals(0.1, usage.getStarBot());
        assertEquals(0, usage.getOther());
        assertEquals(1, new Usage(1.2, 1.1).getSystem());
        assertFalse(new Usage(-1, 0).isAvailable());
        assertFalse(new Usage(Double.POSITIVE_INFINITY, 0).isAvailable());
        assertFalse(new Usage(0.5, Double.NaN).isAvailable());
    }

    /**
     * 通过私有构造器创建服务
     * @param applicationDirectory 本次测试使用的应用目录
     * @return 使用模拟资源查询依赖的服务实例
     * @throws ReflectiveOperationException 私有构造器查询或调用失败时抛出
     */
    private SystemResourceService createService(Path applicationDirectory) throws ReflectiveOperationException {
        Constructor<SystemResourceService> constructor = SystemResourceService.class.getDeclaredConstructor(
                SystemInfo.class, OperatingSystemMXBean.class, Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(systemInfo, operatingSystem, applicationDirectory);
    }
}
