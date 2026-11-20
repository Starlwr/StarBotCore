package com.starlwr.bot.core.event.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * StarBot 配置变更事件测试
 */
class StarBotConfigChangedEventTest {
    /**
     * 修改参数或读取结果的副本时，事件中的新旧值保持不变
     */
    @Test
    void keepsIndependentSnapshotsAndReadOnlyChanges() {
        String key = "starbot.core.command.owners.qq";
        List<String> before = new ArrayList<>(List.of("old-owner"));
        List<String> after = new ArrayList<>(List.of("new-owner"));
        Map<String, StarBotConfigChangedEvent.Change> changes = new LinkedHashMap<>();
        changes.put(key, new StarBotConfigChangedEvent.Change(before, after));
        StarBotConfigChangedEvent event = new StarBotConfigChangedEvent(changes);
        before.clear();
        after.clear();
        changes.clear();
        assertThat(event.getKeys()).containsExactly(key);
        assertThat(event.getOldValue(key, List.class)).isEqualTo(List.of("old-owner"));
        ((List<?>) event.getNewValue(key)).clear();
        ((List<?>) event.getChanges().get(key).getNewValue()).clear();
        assertThat(event.getNewValue(key, List.class)).isEqualTo(List.of("new-owner"));
        assertThatThrownBy(() -> event.getChanges().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> event.getKeys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(event.toString()).contains(key).doesNotContain("old-owner", "new-owner");
    }

    /**
     * 无参创建的事件可以直接查询，设置变更后保留只读副本
     */
    @Test
    void setsReadOnlyChangesAfterNoArgsConstruction() {
        StarBotConfigChangedEvent event = new StarBotConfigChangedEvent();
        String key = "starbot.core.command.prefix";
        assertThat(event.getChanges()).isEmpty();
        assertThat(event.isChanged(key)).isFalse();
        assertThat(event.getNewValue(key)).isNull();

        Map<String, StarBotConfigChangedEvent.Change> changes = new LinkedHashMap<>();
        changes.put(key, new StarBotConfigChangedEvent.Change("", "!"));
        event.setChanges(changes);
        changes.clear();
        assertThat(event.getNewValue(key, String.class)).isEqualTo("!");
        assertThatThrownBy(() -> event.getChanges().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 根据完整键名或父路径查询变更，替换父对象时仅标记值发生变化的子项
     */
    @Test
    void matchesChangedKeysAndTheirParents() {
        StarBotConfigChangedEvent event = new StarBotConfigChangedEvent(Map.of(
                "starbot.core.command.prefix", new StarBotConfigChangedEvent.Change("", "!"),
                "starbot.example", new StarBotConfigChangedEvent.Change(
                        Map.of("enabled", true, "label", "same"), Map.of("enabled", false, "label", "same"))));
        assertThat(event.isChanged("starbot.core.command.prefix")).isTrue();
        assertThat(event.isChanged("starbot.core.command")).isTrue();
        assertThat(event.isChanged("starbot.core.commander")).isFalse();
        assertThat(event.isChanged("starbot.example.enabled")).isTrue();
        assertThat(event.isChanged("starbot.example.label")).isFalse();
        assertThat(event.isChanged("starbot.example.unknown")).isFalse();
        assertThat(event.isChanged(null)).isFalse();
        assertThat(event.isChanged(" ")).isFalse();
        assertThat(event.isAnyChanged("starbot.unknown", "starbot.core.command")).isTrue();
        assertThat(event.isAnyChanged((String[]) null)).isFalse();
        assertThat(event.getOldValue("starbot.unknown")).isNull();
        assertThat(event.getNewValue("starbot.unknown")).isNull();
    }
}
