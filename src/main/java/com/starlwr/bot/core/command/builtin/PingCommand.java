package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.StarBotCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * 连通性测试命令
 */
@Component
public class PingCommand implements StarBotCommand {
    private final BuildProperties build;

    @Autowired
    public PingCommand(BuildProperties build) {
        this.build = build;
    }

    @Override
    public String getId() {
        return "starbot.builtin.ping";
    }

    @Override
    public String getName() {
        return "starbot";
    }

    @Override
    public String getDescription() {
        return "测试 StarBot 连通性";
    }

    @Override
    public int getArgumentCount() {
        return 0;
    }

    @Override
    public void execute(CommandContext context) {
        context.reply("Running on StarBot v" + build.getVersion());
    }
}
