package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandPermission;
import com.starlwr.bot.core.command.CommandScope;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.command.StarBotCommandPolicy;
import com.starlwr.bot.core.command.StarBotCommandRegistry;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 启用命令
 */
@Component
public class EnableCommand implements StarBotCommand {
    private final StarBotCommandRegistry registry;

    private final StarBotCommandPolicy policy;

    @Autowired
    public EnableCommand(StarBotCommandRegistry registry, StarBotCommandPolicy policy) {
        this.registry = registry;
        this.policy = policy;
    }

    @Override
    public String getId() {
        return "starbot.builtin.enable";
    }

    @Override
    public String getName() {
        return "启用";
    }

    @Override
    public List<String> getAliases() {
        return List.of("enable");
    }

    @Override
    public String getDescription() {
        return "启用当前群的指定命令";
    }

    @Override
    public String getUsage() {
        return "启用 <命令ID或名称或别名>";
    }

    @Override
    public int getArgumentCount() {
        return 1;
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
        String query = context.getArguments().get(0);
        List<StarBotCommand> matches = registry.find(query);
        if (CollectionUtils.isEmpty(matches)) {
            context.reply("要启用的命令不存在~");
            return;
        }

        if (matches.size() > 1) {
            context.reply("找到多个匹配的命令，请使用完整命令 ID 启用: \n" + matches.stream().map(StarBotCommand::getId).collect(Collectors.joining("\n")));
            return;
        }

        StarBotCommand command = matches.get(0);
        boolean changed = policy.setGroupDisabled(context.getPlatform(), context.getGroupId(), command.getId(), false);
        if (changed) {
            context.reply("命令已启用~");
        } else {
            context.reply("此命令已经是启用状态~");
        }
    }
}
