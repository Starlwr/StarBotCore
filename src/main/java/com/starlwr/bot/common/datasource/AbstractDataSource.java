package com.starlwr.bot.common.datasource;

import com.starlwr.bot.common.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.common.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.common.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.common.exception.DataSourceException;
import com.starlwr.bot.common.model.PushTarget;
import com.starlwr.bot.common.model.PushUser;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据源抽象类
 */
@Slf4j
public abstract class AbstractDataSource {
    @Resource
    protected ApplicationEventPublisher eventPublisher;

    @Resource
    private DataSourceServiceRegistry dataSourceServiceRegistry;

    protected final List<PushUser> users = new ArrayList<>();

    private final Map<String, Map<Long, PushUser>> userMap = new HashMap<>();

    /**
     * 加载数据源，读取完毕后需调用 add 方法将推送用户添加至数据源中
     * PushUser 仅须填充 uid, platform, enabled, targets 字段
     * PushTarget 仅须填充 user, platform, type, num, enabled, messages 字段
     * PushMessage 仅须填充 target, event, handler, params, enabled 字段
     */
    public abstract void load();

    /**
     * 添加推送用户
     * @param user 推送用户
     */
    public synchronized void add(@NonNull PushUser user) {
        add(Collections.singletonList(user));
    }

    /**
     * 批量添加推送用户
     * @param users 推送用户列表
     */
    public synchronized void add(@NonNull List<PushUser> users) {
        users.removeIf(user -> !user.getEnabled());

        if (new HashSet<>(users).size() != users.size()) {
            throw new DataSourceException("推送用户列表中存在重复的用户");
        }

        for (PushUser user: users) {
            if (this.userMap.containsKey(user.getPlatform()) && this.userMap.get(user.getPlatform()).containsKey(user.getUid())) {
                throw new DataSourceException("数据源中已存在该推送用户 (平台: " + user.getPlatform() + ", UID: " + user.getUid() + "), 无法重复添加");
            }
        }

        for (PushUser user: users) {
            user.getTargets().removeIf(target -> !target.getEnabled());
            for (PushTarget target: user.getTargets()) {
                target.getMessages().removeIf(message -> !message.getEnabled());
            }
        }

        Map<String, List<PushUser>> platformMap = users.stream().collect(Collectors.groupingBy(PushUser::getPlatform));
        for (String platform: platformMap.keySet()) {
            dataSourceServiceRegistry.getDataSourceService(platform)
                    .orElseThrow(() -> new DataSourceException("未找到数据源服务实现类: " + platform))
                    .completePushUsers(platformMap.get(platform));
        }

        this.users.addAll(users);
        for (PushUser user: users) {
            this.userMap.computeIfAbsent(user.getPlatform(), k -> new HashMap<>()).put(user.getUid(), user);

            log.info("新增推送用户: (UID: {}, 昵称: {}, 房间号: {}, 平台: {})", user.getUid(), user.getUname(), user.getRoomId(), user.getPlatform());

            StarBotDataSourceAddEvent event = new StarBotDataSourceAddEvent(user, Instant.now());
            eventPublisher.publishEvent(event);
        }
    }

    /**
     * 移除推送用户
     * @param user 推送用户
     */
    public synchronized void remove(@NonNull PushUser user) {
        if (!this.userMap.containsKey(user.getPlatform()) || !this.userMap.get(user.getPlatform()).containsKey(user.getUid())) {
            throw new DataSourceException("数据源中不存在该推送用户 (平台: " + user.getPlatform() + ", UID: " + user.getUid() + "), 无需移除");
        }

        dataSourceServiceRegistry.getDataSourceService(user.getPlatform())
                .orElseThrow(() -> new DataSourceException("未找到数据源服务实现类: " + user.getPlatform()))
                .completePushUser(user);

        this.userMap.get(user.getPlatform()).remove(user.getUid());
        if (this.userMap.get(user.getPlatform()).isEmpty()) {
            this.userMap.remove(user.getPlatform());
        }
        this.users.remove(user);

        log.info("移除推送用户: (UID: {}, 昵称: {}, 房间号: {}, 平台: {})", user.getUid(), user.getUname(), user.getRoomId(), user.getPlatform());

        StarBotDataSourceRemoveEvent event = new StarBotDataSourceRemoveEvent(user, Instant.now());
        eventPublisher.publishEvent(event);
    }

    /**
     * 更新推送用户
     * @param user 推送用户
     */
    public synchronized void update(@NonNull PushUser user) {
        if (user.getEnabled() && (!this.userMap.containsKey(user.getPlatform()) || !this.userMap.get(user.getPlatform()).containsKey(user.getUid()))) {
            add(user);
            return;
        }

        if (!user.getEnabled() && this.userMap.containsKey(user.getPlatform()) && this.userMap.get(user.getPlatform()).containsKey(user.getUid())) {
            remove(user);
            return;
        }

        user.getTargets().removeIf(target -> !target.getEnabled());
        for (PushTarget target: user.getTargets()) {
            target.getMessages().removeIf(message -> !message.getEnabled());
        }

        dataSourceServiceRegistry.getDataSourceService(user.getPlatform())
                .orElseThrow(() -> new DataSourceException("未找到数据源服务实现类: " + user.getPlatform()))
                .completePushUser(user);

        PushUser oldUser = this.userMap.get(user.getPlatform()).get(user.getUid());
        if (oldUser == null) {
            throw new DataSourceException("数据源中不存在该推送用户 (平台: " + user.getPlatform() + ", UID: " + user.getUid() + "), 无法更新");
        }

        this.userMap.get(user.getPlatform()).put(user.getUid(), user);

        log.info("更新推送用户: (UID: {}, 昵称: {}, 房间号: {}, 平台: {})", user.getUid(), user.getUname(), user.getRoomId(), user.getPlatform());

        StarBotDataSourceUpdateEvent event = new StarBotDataSourceUpdateEvent(user, Instant.now());
        eventPublisher.publishEvent(event);
    }

    /**
     * 获取推送用户列表
     * @return 推送用户列表
     */
    public List<PushUser> getAllUsers() {
        return new ArrayList<>(users);
    }

    /**
     * 根据直播平台获取推送用户列表
     * @param livePlatform 直播平台
     * @return 推送用户列表
     */
    public List<PushUser> getUsers(@NonNull String livePlatform) {
        return Optional.ofNullable(this.userMap.get(livePlatform))
                .map(map -> new ArrayList<>(map.values()))
                .orElse(new ArrayList<>());
    }

    /**
     * 根据直播平台和 UID 获取推送用户
     * @param livePlatform 直播平台
     * @param uid UID
     * @return 推送用户
     */
    public Optional<PushUser> getUser(@NonNull String livePlatform, @NonNull Long uid) {
        if (this.userMap.containsKey(livePlatform)) {
            return Optional.ofNullable(this.userMap.get(livePlatform).get(uid));
        } else {
            return Optional.empty();
        }
    }
}
