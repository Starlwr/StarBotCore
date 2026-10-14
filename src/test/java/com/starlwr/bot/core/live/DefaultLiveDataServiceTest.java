package com.starlwr.bot.core.live;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.live.common.DanmuEvent;
import com.starlwr.bot.core.event.model.LiveStreamerInfo;
import com.starlwr.bot.core.event.model.UserInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认直播数据服务测试
 * <p>
 * 覆盖数据加载、定时及关闭保存、并发读写与持久化快照、查询结果隔离等行为。
 */
class DefaultLiveDataServiceTest {
    /**
     * 测试直播平台
     */
    private static final String PLATFORM = "test";

    /**
     * 测试主播 UID
     */
    private static final String SOURCE_UID = "streamer:001";

    /**
     * 测试观众 UID
     */
    private static final String SENDER_UID = "viewer:002";

    /**
     * 并发写线程数量
     */
    private static final int WRITER_COUNT = 4;

    /**
     * 每个写线程添加的事件数量
     */
    private static final int EVENTS_PER_WRITER = 300;

    /**
     * 并发保存线程数量
     */
    private static final int SAVER_COUNT = 2;

    /**
     * 每个保存线程生成的快照数量
     */
    private static final int SAVES_PER_THREAD = 80;

    @TempDir
    private Path tempDir;

    /**
     * 测试缓存读写与持久化快照并发执行
     * <p>
     * 启动 4 个写线程、2 个读线程和 2 个保存线程同时操作同一服务实例；写线程共添加 1200 条弹幕，
     * 保存线程生成 160 份快照。验证所有任务均能在超时时间内完成、事件没有丢失，并且最终 JSON 文件可以正常解析。
     */
    @Test
    void concurrentReadWriteAndSaveShouldProduceCompleteSnapshots() throws Exception {
        long startTime = System.nanoTime();
        Path dataPath = tempDir.resolve("concurrent-data.json");
        DefaultLiveDataService service = createService(dataPath);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int writer = 0; writer < WRITER_COUNT; writer++) {
                int writerId = writer;
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int i = 0; i < EVENTS_PER_WRITER; i++) {
                        service.addDanmu(createDanmu(writerId + "-" + i));
                        service.setLiveStatus(PLATFORM, SOURCE_UID, i % 2 == 0);
                        service.setLiveStartTime(PLATFORM, SOURCE_UID, i);
                        service.setLiveEndTime(PLATFORM, SOURCE_UID, i + 1L);
                        service.setCustomObject(Map.of("writer", writerId, "value", i),
                                PLATFORM, "Custom", String.valueOf(writerId));
                    }
                }));
            }

            for (int reader = 0; reader < 2; reader++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int i = 0; i < 300; i++) {
                        service.getDanmu(PLATFORM, SOURCE_UID, JSONObject.class);
                        service.getUserDanmu(PLATFORM, SOURCE_UID, SENDER_UID, JSONObject.class);
                        service.getLiveStatus(PLATFORM, SOURCE_UID);
                        service.getCustomObject(Map.class, PLATFORM, "Custom", "0");
                    }
                }));
            }

            for (int saver = 0; saver < SAVER_COUNT; saver++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int i = 0; i < SAVES_PER_THREAD; i++) {
                        Boolean saved = ReflectionTestUtils.invokeMethod(
                                service, "saveCache", dataPath, false);
                        assertEquals(Boolean.TRUE, saved);
                    }
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            int expectedEventCount = WRITER_COUNT * EVENTS_PER_WRITER;
            assertEquals(expectedEventCount,
                    service.getUserDanmu(PLATFORM, SOURCE_UID, SENDER_UID, JSONObject.class).size());
            assertNotNull(JSONObject.parseObject(Files.readString(dataPath)));

            long elapsedMillis = (System.nanoTime() - startTime) / 1_000_000;
            System.out.println("并发读写保存测试完成, 事件数量: " + expectedEventCount
                    + ", 快照数量: " + SAVER_COUNT * SAVES_PER_THREAD
                    + ", 耗时: " + elapsedMillis + " 毫秒");
        } finally {
            service.onContextClosedEvent();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * 测试事件查询结果不会反向修改持久化缓存
     * <p>
     * 分别修改全部事件查询和指定观众查询返回的对象，再执行关闭保存；验证仅用于查询结果的 sender 字段
     * 以及调用方新增的字段均未写入原始事件记录，确保两类查询都返回独立副本。
     */
    @Test
    void queryingEventsShouldNotModifyPersistedEventData() throws Exception {
        Path dataPath = tempDir.resolve("query-data.json");
        DefaultLiveDataService service = createService(dataPath);

        service.addDanmu(createDanmu("hello"));
        List<JSONObject> events = service.getDanmu(PLATFORM, SOURCE_UID, JSONObject.class);

        assertEquals(1, events.size());
        assertEquals(SENDER_UID, events.get(0).getString("sender"));
        events.get(0).put("mutatedFromAllEvents", true);
        List<JSONObject> userEvents = service.getUserDanmu(
                PLATFORM, SOURCE_UID, SENDER_UID, JSONObject.class);
        userEvents.get(0).put("mutatedFromUserEvents", true);

        service.onContextClosedEvent();
        JSONObject storedEvent = JSONObject.parseObject(Files.readString(dataPath))
                .getJSONObject(PLATFORM)
                .getJSONObject("Danmu")
                .getJSONObject(SOURCE_UID)
                .getJSONArray(SENDER_UID)
                .getJSONObject(0);
        assertFalse(storedEvent.containsKey("sender"));
        assertFalse(storedEvent.containsKey("mutatedFromAllEvents"));
        assertFalse(storedEvent.containsKey("mutatedFromUserEvents"));

        System.out.println("事件查询隔离测试完成, 查询结果修改未影响持久化事件");
    }

    /**
     * 测试自定义对象在写入和读取时均与缓存隔离
     * <p>
     * 写入嵌套 Map 后修改原对象，再修改首次读取结果，验证两次修改都不会影响缓存；
     * 同时验证 Integer 标量仍可按原类型正常读取。
     */
    @Test
    @SuppressWarnings("unchecked")
    void customObjectsShouldBeDetachedOnWriteAndRead() {
        DefaultLiveDataService service = createService(tempDir.resolve("custom-data.json"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("value", 1);
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("nested", nested);

        service.setCustomObject(original, "Custom");
        nested.put("value", 2);
        original.put("added", true);

        Map<String, Object> firstRead = service.getCustomObject(Map.class, "Custom").orElseThrow();
        assertEquals(1, ((Map<String, Object>) firstRead.get("nested")).get("value"));
        assertFalse(firstRead.containsKey("added"));

        ((Map<String, Object>) firstRead.get("nested")).put("value", 3);
        firstRead.put("returned", true);

        Map<String, Object> secondRead = service.getCustomObject(Map.class, "Custom").orElseThrow();
        assertEquals(1, ((Map<String, Object>) secondRead.get("nested")).get("value"));
        assertFalse(secondRead.containsKey("returned"));

        service.setCustomObject(42, "Integer");
        assertEquals(42, service.getCustomObject(Integer.class, "Integer").orElseThrow());

        System.out.println("自定义对象隔离测试完成, 嵌套对象和标量读取结果均符合预期");
        service.onContextClosedEvent();
    }

    /**
     * 测试关闭保存始终是同一服务实例的最后一次文件写入
     * <p>
     * 让多个普通保存任务与关闭保存竞争，验证关闭时调度器被停止、后续普通保存被跳过，
     * 并且文件中的最终状态不会被关闭前生成的旧快照覆盖。
     */
    @Test
    void closingSaveShouldRemainTheLastFileWrite() throws Exception {
        Path dataPath = tempDir.resolve("closing-data.json");
        DefaultLiveDataService service = createService(dataPath);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> saves = new ArrayList<>();

        try {
            service.setCustomObject("before-close", "State");
            for (int i = 0; i < 4; i++) {
                saves.add(executor.submit(() -> {
                    await(start);
                    for (int attempt = 0; attempt < 50; attempt++) {
                        ReflectionTestUtils.invokeMethod(service, "saveCache", dataPath, false);
                    }
                }));
            }

            start.countDown();
            service.setCustomObject("final", "State");
            service.onContextClosedEvent();

            ScheduledExecutorService scheduler = (ScheduledExecutorService) ReflectionTestUtils.getField(service, "scheduler");
            assertNotNull(scheduler);
            assertTrue(scheduler.isShutdown());
            Boolean skipped = ReflectionTestUtils.invokeMethod(service, "saveCache", dataPath, false);
            assertEquals(Boolean.FALSE, skipped);

            for (Future<?> save : saves) {
                save.get(10, TimeUnit.SECONDS);
            }

            JSONObject stored = JSONObject.parseObject(Files.readString(dataPath));
            assertEquals("final", stored.getString("State"));
            System.out.println("关闭保存顺序测试完成, 调度器已关闭且最终状态未被旧快照覆盖");
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * 验证非法非空文件阻止加载，且不会覆盖原文件
     */
    @Test
    void invalidJsonFilesFailLoadingWithoutChangingOriginal() throws Exception {
        int index = 0;
        for (String original : new String[]{"broken", "[]", "null", "null trailing"}) {
            Path path = tempDir.resolve("invalid-" + index++ + ".json");
            Files.writeString(path, original);
            StarBotCoreProperties properties = new StarBotCoreProperties();
            properties.getLive().setLiveDataPath(path.toString());
            DefaultLiveDataService service = new DefaultLiveDataService(properties);
            try {
                assertThrows(IllegalStateException.class, service::onApplicationReadyEvent);
                assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(
                        service, "saveCache", path, false));
            } finally {
                service.onContextClosedEvent();
            }
            assertEquals(original, Files.readString(path));
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(4, files.count());
        }
    }

    /**
     * 验证空白文件按空数据加载，关闭时保存有效内容
     */
    @Test
    void blankFilesLoadAsEmptyData() throws Exception {
        int index = 0;
        for (String content : new String[]{"", " ", "\t\r\n ", "\u3000"}) {
            Path path = tempDir.resolve("blank-" + index++ + ".json");
            Files.writeString(path, content);
            StarBotCoreProperties properties = new StarBotCoreProperties();
            properties.getLive().setLiveDataPath(path.toString());
            DefaultLiveDataService service = new DefaultLiveDataService(properties);
            try {
                service.onApplicationReadyEvent();
                assertTrue(service.getLiveStatus("bilibili", "1").isEmpty());
                service.setLiveStatus("bilibili", "1", true);
                assertTrue(service.getLiveStatus("bilibili", "1").orElseThrow());
            } finally {
                service.onContextClosedEvent();
            }
            assertTrue(Files.readString(path).contains("true"));
        }
    }

    /**
     * 验证关闭持久化后仍可正常使用内存数据
     */
    @Test
    void persistenceDisabledStillInitializesUsableMemory() {
        Path path = tempDir.resolve("disabled.json");
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setSaveLiveData(false);
        properties.getLive().setLiveDataPath(path.toString());
        DefaultLiveDataService service = new DefaultLiveDataService(properties);
        try {
            service.onApplicationReadyEvent();
            service.setLiveStatus("bilibili", "1", true);
            assertTrue(service.getLiveStatus("bilibili", "1").orElseThrow());
        } finally {
            service.onContextClosedEvent();
        }
        assertFalse(Files.exists(path));
    }

    /**
     * 验证文件和父目录不存在时从空数据启动，关闭时创建目录并写入空对象
     */
    @Test
    void missingFileStartsWithEmptyDataAndSavesEmptyObject() throws Exception {
        Path path = tempDir.resolve("missing").resolve("data.json");
        assertFalse(Files.exists(path.getParent()));
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(path.toString());
        DefaultLiveDataService service = new DefaultLiveDataService(properties);
        try {
            service.onApplicationReadyEvent();
            assertTrue(service.getLiveStatus("bilibili", "1").isEmpty());
        } finally {
            service.onContextClosedEvent();
        }
        assertTrue(Files.isDirectory(path.getParent()));
        assertEquals("{}", Files.readString(path));
    }

    /**
     * 验证定时保存和清空数据后的关闭保存
     */
    @Test
    void periodicSaveAndClearedCacheShutdown() throws Exception {
        Path path = tempDir.resolve("periodic.json");
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(path.toString());
        properties.getLive().setAutoSaveLiveDataInterval(1);
        DefaultLiveDataService service = new DefaultLiveDataService(properties);
        try {
            service.onApplicationReadyEvent();
            service.setLiveStatus("bilibili", "1", true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(path) || !Files.readString(path).contains("true")) && System.nanoTime() < deadline) {
                Thread.sleep(30);
            }
            assertTrue(Files.readString(path).contains("true"));
            assertTrue(service.deleteCustomObject("bilibili"));
        } finally {
            service.onContextClosedEvent();
        }
        assertEquals("{}", Files.readString(path));
    }

    /**
     * 验证有效文件直接加载，关闭时保存后续变更
     */
    @Test
    void validFileLoadsWithoutBackupAndShutdownPersistsChanges() throws Exception {
        Path path = tempDir.resolve("valid.json");
        Files.writeString(path, "{\"bilibili\":{\"LiveStatus\":{\"1\":true}}}");
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(path.toString());
        DefaultLiveDataService service = new DefaultLiveDataService(properties);
        try {
            service.onApplicationReadyEvent();
            assertTrue(service.getLiveStatus("bilibili", "1").orElseThrow());
            service.setLiveStatus("bilibili", "1", false);
        } finally {
            service.onContextClosedEvent();
        }
        assertTrue(Files.readString(path).contains("false"));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    private DefaultLiveDataService createService(Path dataPath) {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setSaveLiveData(true);
        properties.getLive().setLiveDataPath(dataPath.toString());
        DefaultLiveDataService service = new DefaultLiveDataService(properties);
        service.onApplicationReadyEvent();
        return service;
    }

    private DanmuEvent createDanmu(String content) {
        LiveStreamerInfo source = new LiveStreamerInfo(SOURCE_UID, "streamer", "3001");
        UserInfo sender = new UserInfo(SENDER_UID, "viewer");
        return new DanmuEvent(PLATFORM, source, sender, content);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发测试开始时被中断", e);
        }
    }
}
