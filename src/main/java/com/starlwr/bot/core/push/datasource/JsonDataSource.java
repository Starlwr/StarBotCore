package com.starlwr.bot.core.push.datasource;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import com.starlwr.bot.core.message.PushTargetType;
import com.starlwr.bot.core.push.exception.DataSourceException;
import com.starlwr.bot.core.push.handler.StarBotEventHandlerService;
import com.starlwr.bot.core.push.model.PushMessage;
import com.starlwr.bot.core.push.model.PushTarget;
import com.starlwr.bot.core.push.model.PushUser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.SetUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON 数据源
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "starbot.core.datasource.type", havingValue = "json", matchIfMissing = true)
public class JsonDataSource extends AbstractDataSource {
    private final StarBotCoreProperties properties;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> pendingTask;

    private final AtomicLong lastTriggeredTime = new AtomicLong(0);

    private final long debounceDelayMillis = 1000L;

    @Autowired
    public JsonDataSource(ApplicationEventPublisher eventPublisher, DataSourceServiceRegistry dataSourceServiceRegistry, StarBotEventHandlerService handlerService, StarBotCoreProperties properties) {
        super(eventPublisher, dataSourceServiceRegistry, handlerService);
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "json";
    }

    /**
     * 加载数据源，读取完毕后需调用 add 方法将推送用户添加至数据源中
     * PushUser 仅须填充 uid, platform, enabled, targets 字段
     * PushTarget 仅须填充 user, platform, type, num, enabled, messages 字段
     * PushMessage 仅须填充 target, event, handler, params, enabled 字段
     */
    @Override
    public void load() {
        log.info("已选用 JSON 作为数据源");
        log.info("开始从 JSON 中初始化推送配置");

        String path = properties.getDatasource().getJsonPath();
        try {
            List<PushUser> users = parse(Files.readString(Path.of(path)));
            add(users);
        } catch (NoSuchFileException e) {
            throw new DataSourceException("数据源 JSON 文件不存在, 请检查配置的路径是否正确: " + path);
        } catch (Exception e) {
            throw new DataSourceException("读取数据源 JSON 文件异常", e);
        }

        log.info("成功从 JSON 中导入了 {} 个主播", this.users.size());

        eventPublisher.publishEvent(new StarBotDataSourceLoadCompleteEvent(new ArrayList<>(this.users)));

        if (properties.getDatasource().isJsonAutoReload()) {
            watchFileUpdate();
        }
    }

    /**
     * 监听 JSON 文件更新
     */
    private void watchFileUpdate() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            Path jsonPath = Paths.get(properties.getDatasource().getJsonPath()).toAbsolutePath();
            Path parentPath = jsonPath.getParent();
            parentPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);

            executor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();
                            if (changed != null && changed.getFileName().equals(jsonPath.getFileName())) {
                                if (pendingTask != null && !pendingTask.isDone()) {
                                    pendingTask.cancel(false);
                                }

                                pendingTask = scheduler.schedule(() -> {
                                    Thread.currentThread().setName("json-watcher");
                                    long now = System.currentTimeMillis();
                                    long last = lastTriggeredTime.getAndSet(now);
                                    if (now - last >= debounceDelayMillis) {
                                        log.info("检测到数据源 JSON 文件已更新, 开始从 JSON 中重载推送配置");
                                        reload();
                                    }
                                }, debounceDelayMillis, TimeUnit.MILLISECONDS);
                            }
                        }

                        if (!key.reset()) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("监听数据源 JSON 文件更新异常", e);
                    }
                }
            });
        } catch (Exception e) {
            log.error("监听数据源 JSON 文件更新异常", e);
        }
    }

    /**
     * 重载数据源
     */
    private void reload() {
        String path = properties.getDatasource().getJsonPath();
        try {
            List<PushUser> users = parse(Files.readString(Path.of(path)));
            Set<PushUser> newUsers = new HashSet<>(users);
            if (newUsers.size() != users.size()) {
                throw new DataSourceException("推送用户列表中存在重复的用户");
            }

            Set<PushUser> oldUsers = new HashSet<>(this.users);
            List<PushUser> addUsers = new ArrayList<>(SetUtils.difference(newUsers, oldUsers));
            List<PushUser> removeUsers = new ArrayList<>(SetUtils.difference(oldUsers, newUsers));
            List<PushUser> updateUsers = new ArrayList<>(SetUtils.intersection(newUsers, oldUsers));

            add(addUsers);
            for (PushUser user : removeUsers) {
                remove(user);
            }
            update(updateUsers);
        } catch (Exception e) {
            log.error("重载数据源 JSON 文件异常", e);
        }
    }

    /**
     * 解析 JSON 数据
     * @param json JSON 数据
     * @return 解析出的 PushUser 列表
     */
    protected List<PushUser> parse(String json) {
        List<PushUser> users = new ArrayList<>();

        List<String> userRequiredFields = List.of("uid", "platform");
        List<String> targetRequiredFields = List.of("platform", "type", "num");
        List<String> messageRequiredFields = List.of("handler");

        for (JSONObject userObject : JSON.parseArray(json).toList(JSONObject.class)) {
            for (String field : userRequiredFields) {
                if (!userObject.containsKey(field)) {
                    throw new DataSourceException("数据源 JSON 文件格式错误, " + field + " 字段缺失");
                }
            }

            PushUser user = new PushUser();
            user.setUid(userObject.getString("uid"));
            user.setPlatform(userObject.getString("platform"));
            if (!userObject.containsKey("enabled")) {
                user.setEnabled(true);
            } else {
                user.setEnabled(userObject.getBoolean("enabled"));
            }
            user.setTargets(new ArrayList<>());

            if (userObject.containsKey("targets")) {
                for (JSONObject targetObject : userObject.getJSONArray("targets").toList(JSONObject.class)) {
                    for (String field : targetRequiredFields) {
                        if (!targetObject.containsKey(field)) {
                            throw new DataSourceException("数据源 JSON 文件格式错误, 缺失 " + field + " 字段");
                        }
                    }

                    PushTarget target = new PushTarget();
                    target.setUser(user);
                    target.setPlatform(targetObject.getString("platform"));
                    target.setType(PushTargetType.of(targetObject.getInteger("type")));
                    target.setNum(targetObject.getString("num"));
                    if (!targetObject.containsKey("enabled")) {
                        target.setEnabled(true);
                    } else {
                        target.setEnabled(targetObject.getBoolean("enabled"));
                    }
                    target.setMessages(new ArrayList<>());
                    user.getTargets().add(target);

                    if (targetObject.containsKey("messages")) {
                        for (JSONObject messageObject : targetObject.getJSONArray("messages").toList(JSONObject.class)) {
                            for (String field : messageRequiredFields) {
                                if (!messageObject.containsKey(field)) {
                                    throw new DataSourceException("数据源 JSON 文件格式错误, " + field + " 字段缺失");
                                }
                            }

                            PushMessage message = new PushMessage();
                            message.setTarget(target);
                            message.setHandler(messageObject.getString("handler"));
                            JSONObject params = messageObject.getJSONObject("params");
                            if (params != null) {
                                message.setParams(params.toJSONString());
                            }
                            if (!messageObject.containsKey("enabled")) {
                                message.setEnabled(true);
                            } else {
                                message.setEnabled(messageObject.getBoolean("enabled"));
                            }
                            target.getMessages().add(message);
                        }
                    }
                }
            }

            users.add(user);
        }

        return users;
    }
}
