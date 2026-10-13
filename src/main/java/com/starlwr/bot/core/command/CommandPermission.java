package com.starlwr.bot.core.command;

/**
 * 命令所需执行权限
 */
public enum CommandPermission {
    /**
     * 仅主人
     */
    OWNER,

    /**
     * 主人、当前群群主或管理员，私聊中仅主人可用
     */
    ADMIN,

    /**
     * 所有人
     */
    EVERYONE
}
