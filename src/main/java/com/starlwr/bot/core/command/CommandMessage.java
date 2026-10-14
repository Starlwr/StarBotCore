package com.starlwr.bot.core.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.message.GroupRole;
import com.starlwr.bot.core.message.Message;
import com.starlwr.bot.core.message.PushTargetType;
import com.starlwr.bot.core.message.StarBotMessageSender;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Objects;

/**
 * 命令消息，由各消息平台适配器接收到消息后构建
 */
@Getter
@Builder
public final class CommandMessage {
    /**
     * 推送平台
     */
    private final String platform;

    /**
     * 机器人账号
     */
    private final String botId;

    /**
     * 会话类型
     */
    private final PushTargetType type;

    /**
     * 群号，仅群聊使用
     */
    private final String groupId;

    /**
     * 群名称，仅群聊使用
     */
    private final String groupName;

    /**
     * 消息发送者账号
     */
    private final String senderId;

    /**
     * 消息发送者昵称
     */
    private final String nickname;

    /**
     * 消息发送者群昵称，仅群聊使用
     */
    private final String groupNickName;

    /**
     * 发送者在当前群中的角色，仅群聊使用
     */
    @Builder.Default
    private final GroupRole role = GroupRole.UNKNOWN;

    /**
     * 原始消息 ID
     */
    private final String messageId;

    /**
     * 包含命令前缀和占位符的标准化消息内容
     */
    private final String content;

    /**
     * 是否包含对机器人的提及 (@)
     */
    private final boolean mentioned;

    /**
     * 是否为回复机器人的消息
     */
    private final boolean replyToBot;

    /**
     * 消息发送时间戳
     */
    private final Instant timestamp;

    /**
     * 原始消息事件
     */
    private final JSONObject rawMessage;

    /**
     * 消息发送器
     */
    @Getter(AccessLevel.NONE)
    private final StarBotMessageSender messageSender;

    /**
     * 自动根据会话类型获取回复目标账号或群号
     * @return 回复目标账号或群号
     */
    public String getTargetId() {
        return type == PushTargetType.GROUP ? groupId : senderId;
    }

    /**
     * 获取发送者昵称
     * @return 发送者昵称
     */
    public String getNickname() {
        return StringUtils.defaultString(nickname);
    }

    /**
     * 获取群名称
     * @return 群名称
     */
    public String getGroupName() {
        return StringUtils.defaultString(groupName);
    }

    /**
     * 获取消息发送者群昵称
     * @return 消息发送者群昵称
     */
    public String getGroupNickName() {
        return StringUtils.defaultString(groupNickName);
    }

    /**
     * 向原会话回复，支持占位符
     * @param content 可包含占位符的消息内容
     */
    public void reply(@NonNull String content) {
        Objects.requireNonNull(messageSender, "消息发送器不能为空");
        Message.create(getPlatform(), getType(), getTargetId(), content).forEach(messageSender::send);
    }
}
