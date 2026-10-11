package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.enums.CommandPermission;
import com.starlwr.bot.core.enums.CommandScope;
import com.starlwr.bot.core.model.CommandContext;
import com.starlwr.bot.core.service.StarBotCommandPolicy;
import com.starlwr.bot.core.service.StarBotCommandRegistry;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 禁用命令
 */
@Component
public class DisableCommand implements StarBotCommand {
    private final StarBotCommandRegistry registry;

    private final StarBotCommandPolicy policy;

    @Autowired
    public DisableCommand(StarBotCommandRegistry registry, StarBotCommandPolicy policy) {
        this.registry = registry;
        this.policy = policy;
    }

    @Override
    public String getId() {
        return "starbot.builtin.disable";
    }

    @Override
    public String getName() {
        return "禁用";
    }

    @Override
    public List<String> getAliases() {
        return List.of("disable");
    }

    @Override
    public String getDescription() {
        return "禁用当前群的指定命令";
    }

    @Override
    public String getUsage() {
        return "禁用 <命令ID或名称或别名>";
    }

    @Override
    public CommandPermission getPermission() {
        return CommandPermission.ADMIN;
    }

    @Override
    public CommandScope getScope() {
        return CommandScope.GROUP;
    }

    @Override
    public boolean isDisableable() {
        return false;
    }

    @Override
    public void execute(CommandContext context) {
        List<String> arguments = context.getArguments();
        if (arguments.size() != 1) {
            context.reply("用法: " + context.getPrefix() + getUsage());
            return;
        }

        String query = arguments.get(0);
        List<StarBotCommand> matches = registry.find(query);
        if (CollectionUtils.isEmpty(matches)) {
            context.reply("要禁用的命令不存在~");
            return;
        }

        if (matches.size() > 1) {
            context.reply("找到多个匹配的命令，请使用完整命令 ID 禁用: \n" + matches.stream().map(StarBotCommand::getId).collect(Collectors.joining("\n")));
            return;
        }

        StarBotCommand command = matches.get(0);
        if (!command.isDisableable()) {
            context.reply("此命令不可禁用~");
            return;
        }

        if (command.getScope() == CommandScope.PRIVATE) {
            context.reply("此命令仅私聊可用, 无需禁用~");
            return;
        }

        boolean changed = policy.setGroupDisabled(context.getPlatform(), context.getGroupId(), command.getId(), true);
        if (changed) {
            context.reply("命令已禁用~");
        } else {
            context.reply("此命令已经是禁用状态~");
        }
    }
}
