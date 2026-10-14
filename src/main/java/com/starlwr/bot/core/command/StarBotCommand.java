package com.starlwr.bot.core.command;

import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.aop.support.AopUtils;

import java.util.List;

/**
 * StarBot 命令接口，命令均应实现此接口，并使用 {@link StarBotComponent} 注解注册至 Spring 容器中
 */
public interface StarBotCommand {
    /**
     * 命令唯一标识，默认为实现类的全路径类名
     * @return 命令唯一标识
     */
    default String getId() {
        return AopUtils.getTargetClass(this).getName();
    }

    /**
     * 命令名称，默认情况下会作为命令唤醒词
     * @return 命令名称
     */
    String getName();

    /**
     * 命令别名，默认情况下会作为命令唤醒词
     * @return 命令别名列表
     */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * 命令简介
     * @return 命令简介
     */
    String getDescription();

    /**
     * 命令用法说明，默认为命令名称
     * @return 命令用法说明
     */
    default String getUsage() {
        return getName();
    }

    /**
     * 命令所需参数个数，参数自动按空白分隔，非负数表示必须恰好具有该数量的参数，参数数量不符将不会触发命令，默认 -1 表示不限制，由命令自行处理
     * @return 命令参数个数
     */
    default int getArgumentCount() {
        return -1;
    }

    /**
     * 执行命令所需的默认权限，默认所有人均可使用，可通过 starbot.core.command.permissions 按命令 ID 覆盖
     * @return 执行命令所需的默认权限
     */
    default CommandPermission getPermission() {
        return CommandPermission.EVERYONE;
    }

    /**
     * 命令适用的会话类型，默认所有会话类型均可使用
     * @return 命令适用的会话类型
     */
    default CommandScope getScope() {
        return CommandScope.ALL;
    }

    /**
     * 是否允许通过禁用命令禁用该命令，配置文件中的全局禁用配置不受此限制，默认可禁用
     * @return 是否允许通过禁用命令禁用该命令
     */
    default boolean isDisableable() {
        return true;
    }

    /**
     * 命令触发规则，应快速且无副作用，权限、禁用等规则已由 StarBot 进行统一检查，无需在此处判断，默认匹配命令名称和命令别名
     * @param context 命令上下文
     * @return 是否触发命令
     */
    default boolean isTrigger(CommandContext context) {
        String name = context.getCommandName();
        return getName().equalsIgnoreCase(name) || getAliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(name));
    }

    /**
     * 触发命令后命令执行的方法
     * @param context 命令上下文
     */
    void execute(CommandContext context);
}
