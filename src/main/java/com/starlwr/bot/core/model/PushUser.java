package com.starlwr.bot.core.model;

import com.starlwr.bot.core.enums.LivePlatform;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 推送用户
 */
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
}
