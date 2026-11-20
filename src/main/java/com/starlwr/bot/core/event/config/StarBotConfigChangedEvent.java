package com.starlwr.bot.core.event.config;

import com.starlwr.bot.core.config.ConfigValueConverter;
import com.starlwr.bot.core.event.StarBotInternalBaseEvent;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * StarBot 配置变更事件
 */
@Getter
@NoArgsConstructor
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class StarBotConfigChangedEvent extends StarBotInternalBaseEvent {
    /**
     * 配置变更映射
     */
    private Map<String, Change> changes = Collections.emptyMap();

    /**
     * 单项配置变更
     */
    @EqualsAndHashCode(doNotUseGetters = true)
    public static final class Change {
        /**
         * 重载前的生效值
         */
        private final Object oldValue;

        /**
         * 本次重载后的生效值
         */
        private final Object newValue;

        public Change(Object oldValue, Object newValue) {
            this.oldValue = ConfigValueConverter.toTree(oldValue);
            this.newValue = ConfigValueConverter.toTree(newValue);
        }

        /**
         * 读取重载前的生效值
         * @return 重载前生效值的独立副本
         */
        public Object getOldValue() {
            return ConfigValueConverter.toTree(oldValue);
        }

        /**
         * 读取本次重载后的生效值
         * @return 重载后生效值的独立副本
         */
        public Object getNewValue() {
            return ConfigValueConverter.toTree(newValue);
        }
    }

    public StarBotConfigChangedEvent(Map<String, Change> changes) {
        setChanges(changes);
    }

    /**
     * 设置配置变更
     * @param changes 配置变更映射
     */
    public void setChanges(Map<String, Change> changes) {
        this.changes = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(changes)));
    }

    /**
     * 获取本次发生变化的配置键
     * @return 配置键集合
     */
    @ToString.Include(name = "keys")
    public Set<String> getKeys() {
        return changes.keySet();
    }

    /**
     * 读取指定配置在本次变更前的值
     * @param key 完整配置键
     * @return 旧值，键不存在时返回 null
     */
    public Object getOldValue(String key) {
        Change change = changes.get(key);
        return change == null ? null : change.getOldValue();
    }

    /**
     * 读取本次变更前的值并转换类型
     * @param key 完整配置键
     * @param type 目标类型
     * @param <T> 目标值类型
     * @return 旧值
     */
    public <T> T getOldValue(String key, Class<T> type) {
        Change change = changes.get(key);
        return ConfigValueConverter.convert(change == null ? null : change.oldValue, type);
    }

    /**
     * 读取指定配置在本次变更后的值
     * @param key 完整配置键
     * @return 新值，键不存在时返回 null
     */
    public Object getNewValue(String key) {
        Change change = changes.get(key);
        return change == null ? null : change.getNewValue();
    }

    /**
     * 读取本次变更后的值并转换类型
     * @param key 完整配置键
     * @param type 目标类型
     * @param <T> 目标值类型
     * @return 新值
     */
    public <T> T getNewValue(String key, Class<T> type) {
        Change change = changes.get(key);
        return ConfigValueConverter.convert(change == null ? null : change.newValue, type);
    }

    /**
     * 判断指定配置或其子项是否在本次重载中变化
     * @param key 完整配置键或父配置前缀
     * @return 是否发生变化
     */
    public boolean isChanged(String key) {
        if (StringUtils.isBlank(key)) {
            return false;
        }

        // 查询键或其子项出现在变更映射中，直接判定为变更
        String childPrefix = key + ".";
        for (Map.Entry<String, Change> entry : changes.entrySet()) {
            String changedKey = entry.getKey();
            if (changedKey.equals(key) || changedKey.startsWith(childPrefix)) {
                return true;
            }

            // 父对象整体替换时，比较所查询子项的新旧值
            String parentPrefix = changedKey + ".";
            if (!key.startsWith(parentPrefix)) {
                continue;
            }
            Object before = entry.getValue().getOldValue();
            Object after = entry.getValue().getNewValue();
            for (String part : key.substring(parentPrefix.length()).split("\\.")) {
                before = before instanceof Map<?, ?> map ? map.get(part) : null;
                after = after instanceof Map<?, ?> map ? map.get(part) : null;
            }
            if (!Objects.equals(before, after)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断多个配置键中是否至少有一项发生变化
     * @param keys 完整配置键或父配置前缀
     * @return 是否存在变更
     */
    public boolean isAnyChanged(String... keys) {
        return ArrayUtils.isNotEmpty(keys) && Arrays.stream(keys).anyMatch(this::isChanged);
    }
}
