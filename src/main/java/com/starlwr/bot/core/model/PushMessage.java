package com.starlwr.bot.core.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Profile;

import java.util.Objects;

/**
 * 推送消息
 */
@Profile("mysql")
@Getter
@Setter
@Entity
@Table(name = "starbot_push_message")
public class PushMessage {
    /**
     * ID，数据库类数据源使用
     */
    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的推送目标
     */
    @ManyToOne
    @JoinColumn(name = "target_id", referencedColumnName = "id")
    private PushTarget target;

    /**
     * 事件全类名
     */
    @Column(name = "event")
    private String event;

    /**
     * 事件处理器全类名
     */
    @Column(name = "handler")
    private String handler;

    /**
     * JSON 格式推送参数
     */
    @Column(name = "params")
    private String params;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    private Boolean enabled;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PushMessage that)) return false;
        return Objects.equals(event, that.event) && Objects.equals(handler, that.handler) && Objects.equals(params, that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(event, handler, params);
    }

    @Override
    public String toString() {
        return "PushMessage(" + "event=" + event + ", handler=" + handler + ", params=" + params + ", enabled=" + enabled + ")";
    }
}
