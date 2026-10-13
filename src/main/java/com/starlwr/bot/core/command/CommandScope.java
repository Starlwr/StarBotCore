package com.starlwr.bot.core.command;

/**
 * 命令适用的会话类型
 */
public enum CommandScope {
    /**
     * 仅私聊
     */
    PRIVATE,

    /**
     * 仅群聊
     */
    GROUP,

    /**
     * 私聊和群聊
     */
    ALL
}
