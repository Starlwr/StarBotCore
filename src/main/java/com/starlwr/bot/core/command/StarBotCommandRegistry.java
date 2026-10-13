package com.starlwr.bot.core.command;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令注册表
 */
@Slf4j
@Service
public class StarBotCommandRegistry {
    private final ApplicationContext applicationContext;

    @Getter
    private List<StarBotCommand> commands = List.of();

    @Autowired
    public StarBotCommandRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 加载命令
     */
    @Order(0)
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshedEvent() {
        log.info("开始注册命令");
        Map<String, StarBotCommand> unique = new LinkedHashMap<>();
        for (StarBotCommand command : applicationContext.getBeansOfType(StarBotCommand.class).values()) {
            try {
                String id = command.getId();
                if (StringUtils.isBlank(id)) {
                    log.error("命令 ID 为空, 跳过注册当前命令: {}", command.getClass().getName());
                    continue;
                }
                if (StringUtils.isBlank(command.getName())) {
                    log.error("命令名称为空, 跳过注册当前命令: {}", command.getClass().getName());
                    continue;
                }
                if (unique.putIfAbsent(id, command) != null) {
                    log.error("重复的命令 ID: {}，跳过注册当前命令: {}", id, command.getClass().getName());
                    continue;
                }
                log.info("已注册 {} 命令: {}", command.getName(), command.getId());
            } catch (Exception e) {
                log.error("注册命令失败, 跳过注册当前命令: {}", command.getClass().getName(), e);
            }
        }
        commands = List.copyOf(unique.values());
        log.info("成功注册了 {} 个命令", commands.size());
    }

    /**
     * 查找命令
     * @param query 命令 ID、名称或别名
     * @return 匹配的命令列表
     */
    public List<StarBotCommand> find(String query) {
        for (StarBotCommand command : commands) {
            if (command.getId().equals(query)) {
                return List.of(command);
            }
        }

        return commands.stream()
                .filter(command -> command.getName().equalsIgnoreCase(query) || command.getAliases()
                        .stream()
                        .anyMatch(alias -> alias.equalsIgnoreCase(query)))
                .toList();
    }
}
