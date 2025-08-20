package com.starlwr.bot.core.model;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.dynamic.StarBotBaseDynamicEvent;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推送用户
 */
@Slf4j
@Profile("mysql")
@Getter
@Setter
@Entity
@Table(name = "starbot_push_user")
public class PushUser {
    /**
     * ID，数据库类数据源使用
     */
    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * UID
     */
    @Column(name = "uid")
    private Long uid;

    /**
     * 昵称，非必填，会自动获取
     */
    @Column(name = "uname")
    private String uname;

    /**
     * 房间号，非必填，会自动获取
     */
    @Column(name = "room_id")
    private Long roomId;

    /**
     * 头像，非必填，会自动获取
     */
    @Transient
    private String face;

    /**
     * 直播平台，请优先从 {@link LivePlatform} 中获取，若不存在可使用自定义字符串
     */
    @Column(name = "platform")
    private String platform;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    private Boolean enabled;

    /**
     * 关联的推送目标
     */
    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PushTarget> targets = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PushUser pushUser)) return false;
        return Objects.equals(uid, pushUser.uid) && Objects.equals(platform, pushUser.platform);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, platform);
    }

    @Override
    public String toString() {
        return "PushUser(" + "uid=" + uid + ", uname=" + uname + ", roomId=" + roomId + ", face=" + face + ", platform=" + platform + ", enabled=" + enabled + ", targets=" + targets + ")";
    }

    public String getRoomIdString() {
        return roomId == null ? "未开通" : roomId.toString();
    }

    /**
     * 检查推送用户是否监听直播事件
     * @return 是否监听直播事件
     */
    public boolean hasEnabledLiveEvent() {
        Set<String> events = targets.stream()
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEvent)
                .collect(Collectors.toSet());

        for (String event: events) {
            try {
                Class<?> clazz = Class.forName(event, false, Thread.currentThread().getContextClassLoader());
                if (StarBotBaseLiveEvent.class.isAssignableFrom(clazz)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    /**
     * 检查推送用户是否监听动态更新事件
     * @return 是否监听动态更新事件
     */
    public boolean hasEnabledDynamicEvent() {
        Set<String> events = targets.stream()
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEvent)
                .collect(Collectors.toSet());

        for (String event: events) {
            try {
                Class<?> clazz = Class.forName(event, false, Thread.currentThread().getContextClassLoader());
                if (StarBotBaseDynamicEvent.class.isAssignableFrom(clazz)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }
}
