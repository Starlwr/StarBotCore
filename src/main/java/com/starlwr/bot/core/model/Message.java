package com.starlwr.bot.core.model;

import com.starlwr.bot.core.enums.PushPlatform;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.util.StringUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 消息，请使用 create 方法创建消息列表以自动处理 {next} 占位符
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Message {
    /**
     * 推送平台，请优先从 {@link PushPlatform} 中获取，若不存在可使用自定义字符串
     */
    private String platform;

    /**
     * 推送目标类型
     */
    private PushTargetType type;

    /**
     * 账号或群号，根据推送目标类型而定
     */
    private Long num;

    /**
     * 可包含占位符的消息内容
     */
    private String content;

    /**
     * StarBot 内部消息 ID，发送前自动由 StarBot 生成，无需手动设置
     */
    private Long id;

    /**
     * 时间戳
     */
    private Instant timestamp;

    /**
     * 创建消息列表，自动处理 {next} 占位符
     * @param platform 推送平台，请优先从 {@link PushPlatform} 中获取，若不存在可使用自定义字符串
     * @param type 推送目标类型
     * @param num 账号或群号，根据推送目标类型而定
     * @param content 可包含占位符的消息内容
     * @return 消息列表
     */
    public static List<Message> create(String platform, PushTargetType type, Long num, String content) {
        return create(platform, type, num, content, Instant.now());
    }

    /**
     * 创建消息列表，自动处理 {next} 占位符
     * @param platform 推送平台，请优先从 {@link PushPlatform} 中获取，若不存在可使用自定义字符串
     * @param type 推送目标类型
     * @param num 账号或群号，根据推送目标类型而定
     * @param content 可包含占位符的消息内容
     * @param timestamp 时间戳
     * @return 消息列表
     */
    public static List<Message> create(String platform, PushTargetType type, Long num, String content, Instant timestamp) {
        List<Message> messages = new ArrayList<>();

        String[] parts = content.split("\\{next}");
        for (String part : parts) {
            if (StringUtil.isEmpty(part)) {
                continue;
            }

            Message message = new Message();
            message.setPlatform(platform);
            message.setType(type);
            message.setNum(num);
            message.setContent(part);
            message.setTimestamp(timestamp);
            messages.add(message);
        }

        return messages;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Message message)) return false;
        return Objects.equals(platform, message.platform) && type == message.type && Objects.equals(num, message.num) && Objects.equals(content, message.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(platform, type, num, content);
    }

    /**
     * 获取消息的展示字符串
     * @return 消息的展示字符串
     */
    public String getDisplay() {
        if (StringUtil.isBlank(content)) {
            return "";
        }

        return content.replaceAll("\\{face=.+?}", "[表情]")
                .replace("{at=all}", "@全体成员 ")
                .replaceAll("\\{at=(.*?)}", "@$1")
                .replaceAll("\\{image_url=.*?}", "[图片]")
                .replaceAll("\\{image_path=.*?}", "[图片]")
                .replaceAll("\\{image_base64=.*?}", "[图片]");
    }
}
