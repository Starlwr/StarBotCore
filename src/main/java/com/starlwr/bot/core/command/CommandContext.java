package com.starlwr.bot.core.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.message.GroupRole;
import com.starlwr.bot.core.message.PushTargetType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令上下文，供命令使用
 */
@Getter
public final class CommandContext {
    private static final Pattern IGNORED_ELEMENT_PATTERN = Pattern.compile("(?U)\\s*\\{(?:at|image_(?:url|path|base64))=[^{}]*}\\s*");

    private static final Pattern IGNORED_ELEMENT_BEFORE_PREFIX_PATTERN = Pattern.compile("(?U)^(?:\\s*\\{(?:at|image_(?:url|path|base64))=[^{}]*}\\s*)*");

    private static final Pattern IMAGE_ELEMENT_PATTERN = Pattern.compile("\\{image_(?:url|path|base64)=[^{}]*}");

    /**
     * 命令消息
     */
    @Getter(AccessLevel.NONE)
    private final CommandMessage message;

    /**
     * 命令前缀
     */
    private final String prefix;

    /**
     * 移除命令前缀后的含占位符的标准化消息内容
     */
    private final String content;

    /**
     * 消息中的命令名称
     */
    private final String commandName;

    /**
     * 消息中命令名称之后的参数文本
     */
    private final String argumentText;

    private CommandContext(CommandMessage message, String prefix, String content, String commandName, String argumentText) {
        this.message = message;
        this.prefix = prefix;
        this.content = content;
        this.commandName = commandName;
        this.argumentText = argumentText;
    }

    /**
     * 尝试创建命令上下文，不匹配命令前缀时返回空
     * @param message 命令消息
     * @param prefix 命令前缀
     * @return 命令上下文
     */
    public static Optional<CommandContext> tryCreate(@NonNull CommandMessage message, String prefix) {
        String normalized = StringUtils.defaultString(message.getContent()).strip();
        Matcher matcher = IGNORED_ELEMENT_BEFORE_PREFIX_PATTERN.matcher(normalized);
        int prefixStart = matcher.lookingAt() ? matcher.end() : 0;
        if (!normalized.startsWith(prefix, prefixStart)) {
            return Optional.empty();
        }

        String content = (normalized.substring(0, prefixStart) + normalized.substring(prefixStart + prefix.length())).strip();
        String text = IGNORED_ELEMENT_PATTERN.matcher(content).replaceAll(" ").strip();
        if (StringUtils.isEmpty(text)) {
            return Optional.empty();
        }

        String[] parts = text.split("(?U)\\s+", 2);
        return Optional.of(new CommandContext(message, prefix, content, parts[0], parts.length == 2 ? parts[1].strip() : ""));
    }

    /**
     * 获取推送平台
     * @return 推送平台
     */
    public String getPlatform() {
        return message.getPlatform();
    }

    /**
     * 获取机器人账号
     * @return 机器人账号
     */
    public String getBotId() {
        return message.getBotId();
    }

    /**
     * 获取会话类型
     * @return 会话类型
     */
    public PushTargetType getType() {
        return message.getType();
    }

    /**
     * 获取群号，仅群聊使用
     * @return 群号
     */
    public String getGroupId() {
        return message.getGroupId();
    }

    /**
     * 获取群名称，仅群聊使用
     * @return 群名称
     */
    public String getGroupName() {
        return message.getGroupName();
    }

    /**
     * 获取消息发送者账号
     * @return 消息发送者账号
     */
    public String getSenderId() {
        return message.getSenderId();
    }

    /**
     * 获取发送者昵称
     * @return 发送者昵称
     */
    public String getNickname() {
        return message.getNickname();
    }

    /**
     * 获取消息发送者群昵称，仅群聊使用
     * @return 消息发送者群昵称
     */
    public String getGroupNickName() {
        return message.getGroupNickName();
    }

    /**
     * 获取发送者在当前群中的角色，仅群聊使用
     * @return 发送者在当前群中的角色
     */
    public GroupRole getRole() {
        return message.getRole();
    }

    /**
     * 获取原始消息 ID
     * @return 原始消息 ID
     */
    public String getMessageId() {
        return message.getMessageId();
    }

    /**
     * 是否包含对机器人的提及 (@)
     * @return 是否包含对机器人的提及
     */
    public boolean isMentioned() {
        return message.isMentioned();
    }

    /**
     * 是否为回复机器人的消息
     * @return 是否为回复机器人的消息
     */
    public boolean isReplyToBot() {
        return message.isReplyToBot();
    }

    /**
     * 获取消息发送时间戳
     * @return 消息发送时间戳
     */
    public Instant getTimestamp() {
        return message.getTimestamp();
    }

    /**
     * 获取原始消息事件
     * @return 原始消息事件
     */
    public JSONObject getRawMessage() {
        return message.getRawMessage();
    }

    /**
     * 自动根据会话类型获取回复目标账号或群号
     * @return 回复目标账号或群号
     */
    public String getTargetId() {
        return message.getTargetId();
    }

    /**
     * 获取消息中按空白分隔的命令参数
     * @return 命令参数列表
     */
    public List<String> getArguments() {
        return StringUtils.isEmpty(argumentText) ? List.of() : List.of(argumentText.split("(?U)\\s+"));
    }

    /**
     * 获取消息中的图片参数
     * @return 图片占位符列表
     */
    public List<String> getImageArguments() {
        List<String> arguments = new ArrayList<>();
        Matcher matcher = IMAGE_ELEMENT_PATTERN.matcher(StringUtils.defaultString(content));
        while (matcher.find()) {
            arguments.add(matcher.group());
        }
        return List.copyOf(arguments);
    }

    /**
     * 向原会话回复，支持占位符
     * @param content 可包含占位符的消息内容
     */
    public void reply(@NonNull String content) {
        if (StringUtils.isNotBlank(getMessageId())) {
            String reply = "{reply=" + getMessageId() + "}";
            List<String> parts = new ArrayList<>();
            for (String part : content.split("\\{next}")) {
                if (StringUtils.isNotEmpty(part)) {
                    parts.add(reply + part);
                }
            }
            message.reply(String.join("{next}", parts));
        } else {
            message.reply(content);
        }
    }
}
