package com.starlwr.bot.core.command;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.message.GroupRole;
import com.starlwr.bot.core.message.PushTargetType;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 命令权限管理
 */
@Slf4j
@Service
public class StarBotCommandPolicy {
    private final StarBotCoreProperties properties;

    private volatile Map<GroupKey, Set<String>> groups;

    /**
     * 命令禁用配置索引
     */
    @Getter
    @RequiredArgsConstructor
    @EqualsAndHashCode
    private static final class GroupKey {
        /**
         * 推送平台
         */
        private final String platform;

        /**
         * 群号
         */
        private final String groupId;
    }

    /**
     * 命令策略检查结果
     */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Decision {
        /**
         * 是否全局禁用
         */
        private final boolean globalDisabled;

        /**
         * 当前群是否禁用
         */
        private final boolean groupDisabled;

        /**
         * 是否适用当前会话类型
         */
        private final boolean scopeAllowed;

        /**
         * 当前用户是否具有执行权限
         */
        private final boolean permissionAllowed;

        /**
         * 命令是否被禁用
         * @return 命令是否被禁用
         */
        public boolean isDisabled() {
            return globalDisabled || groupDisabled;
        }

        /**
         * 命令是否允许执行
         * @return 命令是否允许执行
         */
        public boolean isAllowed() {
            return !isDisabled() && scopeAllowed && permissionAllowed;
        }
    }

    @Autowired
    public StarBotCommandPolicy(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载命令禁用配置
     */
    @Order(-20000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        Path path = Path.of(properties.getCommand().getDisabledCommandPath()).toAbsolutePath().normalize();
        log.info("开始从 {} 加载命令禁用配置", properties.getCommand().getDisabledCommandPath());
        try {
            Map<GroupKey, Set<String>> loaded = new LinkedHashMap<>();
            String content = Files.readString(path);
            if (StringUtils.isBlank(content)) {
                log.warn("命令禁用配置文件 {} 内容为空，使用空配置", path);
            } else {
                JSONArray rules = JSON.parseArray(content);
                if (rules == null) {
                    throw new IllegalArgumentException("命令禁用配置文件格式异常: " + path + ", 请检查后再尝试启动");
                }

                for (Object entry : rules) {
                    if (!(entry instanceof JSONObject rule) || !(rule.get("platform") instanceof String platform) || StringUtils.isBlank(platform) || rule.getString("groupId") == null || !(rule.get("disabled") instanceof JSONArray ids)) {
                        throw new IllegalArgumentException("命令禁用配置文件格式错误, 禁用规则缺少有效的 platform/groupId/disabled, 请检查后再尝试启动: " + entry);
                    }

                    Set<String> commands = new LinkedHashSet<>();
                    for (Object id : ids) {
                        if (!(id instanceof String value) || StringUtils.isBlank(value)) {
                            throw new IllegalArgumentException("命令禁用配置文件格式错误, 配置的命令 ID 无效, 请检查后再尝试启动: " + entry);
                        }
                        commands.add(value);
                    }

                    GroupKey key = new GroupKey(platform, rule.getString("groupId"));
                    if (loaded.putIfAbsent(key, Set.copyOf(commands)) != null) {
                        throw new IllegalArgumentException("命令禁用配置文件格式错误, 配置中存在重复的群, 请检查后再尝试启动: " + entry);
                    }
                }
            }
            groups = Map.copyOf(loaded);
        } catch (NoSuchFileException e) {
            groups = Map.of();
            log.info("命令禁用配置文件不存在，使用空配置: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("读取命令禁用配置失败: " + path + ", 请检查后再尝试启动", e);
        }

        log.info("命令禁用配置加载完成");
    }

    /**
     * 获取命令策略检查结果
     * @param command 命令
     * @param context 命令上下文
     * @return 命令策略检查结果
     */
    public Decision evaluate(StarBotCommand command, CommandContext context) {
        boolean group = context.getType() == PushTargetType.GROUP;
        boolean privateChat = context.getType() == PushTargetType.FRIEND;
        boolean scope = switch (command.getScope()) {
            case ALL -> group || privateChat;
            case GROUP -> group;
            case PRIVATE -> privateChat;
        };
        boolean owner = properties.getCommand().getOwners().getOrDefault(context.getPlatform(), List.of()).contains(context.getSenderId());
        boolean admin = group && (context.getRole() == GroupRole.OWNER || context.getRole() == GroupRole.ADMIN);
        CommandPermission requiredPermission = properties.getCommand().getPermissions().getOrDefault(command.getId(), command.getPermission());
        boolean permission = switch (requiredPermission) {
            case OWNER -> owner;
            case ADMIN -> owner || admin;
            case EVERYONE -> true;
        };

        return new Decision(
                properties.getCommand().getDisabled().contains(command.getId()),
                group && groups.getOrDefault(new GroupKey(context.getPlatform(), context.getGroupId()), Set.of()).contains(command.getId()),
                scope,
                permission
        );
    }

    /**
     * 设置命令禁用状态
     * @param platform 推送平台
     * @param groupId 群号
     * @param commandId 命令 ID
     * @param disabled 是否禁用
     * @return 是否实际修改
     */
    public synchronized boolean setGroupDisabled(String platform, String groupId, String commandId, boolean disabled) {
        GroupKey key = new GroupKey(Objects.requireNonNull(platform), Objects.requireNonNull(groupId));
        Objects.requireNonNull(commandId);

        Map<GroupKey, Set<String>> updated = new HashMap<>(groups);
        Set<String> ids = new HashSet<>(updated.getOrDefault(key, Set.of()));
        boolean changed = disabled ? ids.add(commandId) : ids.remove(commandId);
        if (!changed) {
            return false;
        }

        if (CollectionUtils.isEmpty(ids)) {
            updated.remove(key);
        } else {
            updated.put(key, Set.copyOf(ids));
        }

        groups = Map.copyOf(updated);
        save();
        return true;
    }

    /**
     * 保存命令禁用配置
     */
    private synchronized void save() {
        try {
            Path path = Path.of(properties.getCommand().getDisabledCommandPath()).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());

            JSONArray rules = new JSONArray();
            groups.entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<GroupKey, Set<String>> entry) -> entry.getKey().getPlatform())
                            .thenComparing(entry -> entry.getKey().getGroupId()))
                    .forEach(entry -> rules.add(new JSONObject()
                            .fluentPut("platform", entry.getKey().getPlatform())
                            .fluentPut("groupId", entry.getKey().getGroupId())
                            .fluentPut("disabled", entry.getValue().stream().sorted().toList())));

            Files.writeString(path, rules.toJSONString());
        } catch (Exception e) {
            log.error("保存命令禁用配置失败", e);
        }
    }
}
