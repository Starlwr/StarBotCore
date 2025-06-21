package com.starlwr.bot.core.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;

import java.util.Objects;

/**
 * 推送消息
 */
@Profile("mysql")
@Slf4j
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
    @JSONField(serialize = false)
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
    @Setter(AccessLevel.NONE)
    private String params;

    /**
     * 推送参数解析后的 JSON 对象，自动根据 params 参数解析
     */
    @Transient
    private JSONObject paramsJsonObject;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    private Boolean enabled;

    public void setParams(String params) {
        this.params = params;
        try {
            this.paramsJsonObject = JSON.parseObject(params);
        } catch (Exception e) {
            this.paramsJsonObject = null;
            log.error("解析推送参数失败, 请检查推送参数格式是否正确: {}", params);
        }
    }

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
