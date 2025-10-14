package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 默认直播数据服务实现
 */
@Slf4j
@Service
@Order(-10000)
public class DefaultLiveDataService implements LiveDataService, ApplicationListener<ApplicationEvent> {
    @Resource
    private StarBotCoreProperties properties;

    private JSONObject cache = new JSONObject();

    @Override
    public void onApplicationEvent(@NonNull ApplicationEvent event) {
        if (event instanceof ApplicationReadyEvent) {
            onApplicationReadyEvent();
        } else if (event instanceof ContextClosedEvent) {
            onContextClosedEvent();
        }
    }

    /**
     * 应用启动完成事件
     */
    private void onApplicationReadyEvent() {
        if (properties.getData().isSaveLiveData()) {
            String liveDataPath = properties.getData().getLiveDataPath();
            log.info("开始从 {} 中加载直播数据", liveDataPath);
            try {
                cache = JSONObject.parseObject(Files.readString(Path.of(liveDataPath)));
            } catch (NoSuchFileException e) {
                log.warn("直播数据文件 {} 不存在, 建立新文件", liveDataPath, e);
            } catch (Exception e) {
                log.error("读取直播数据 {} 异常", liveDataPath, e);
            }
            log.info("直播数据加载完成");
        }
    }

    /**
     * 应用关闭事件
     */
    private void onContextClosedEvent() {
        if (properties.getData().isSaveLiveData()) {
            String liveDataPath = properties.getData().getLiveDataPath();
            log.info("开始保存直播数据至 {}", liveDataPath);
            try {
                Files.writeString(Path.of(liveDataPath), cache.toJSONString());
            } catch (Exception e) {
                log.error("保存直播数据至 {} 异常", liveDataPath, e);
            }
            log.info("直播数据已保存至 {}", liveDataPath);
        }
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }

    // ================ 直播间状态 ================

    /**
     * 获取直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 直播间状态，true：已开播，false：未开播
     */
    @Override
    public Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStatus:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getBoolean(String.valueOf(uid)));
    }

    /**
     * 设置直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param status   直播间状态，true：已开播，false：未开播
     */
    @Override
    public void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status) {
        String key = "LiveStatus:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), status);
    }

    // ================ 直播开始时间 ================

    /**
     * 获取最近一场直播开始时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播开始时间戳
     */
    @Override
    public Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStartTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播开始时间戳
     *
     * @param platform  直播平台
     * @param uid       UID
     * @param startTime 最近一场直播开始时间戳
     */
    @Override
    public void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime) {
        String key = "LiveStartTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), startTime);
    }

    // ================ 直播结束时间 ================

    /**
     * 获取最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播结束时间戳
     */
    @Override
    public Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param endTime  最近一场直播结束时间戳
     */
    @Override
    public void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime) {
        String key = "LiveEndTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), endTime);
    }

    /**
     * 删除最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        Optional.ofNullable(cache.getJSONObject(key)).ifPresent(data -> data.remove(String.valueOf(uid)));
    }

    // ================ 其他操作 ================

    /**
     * 重置最近一场直播数据
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void resetLiveData(@NonNull String platform, @NonNull Long uid) {
    }
}
