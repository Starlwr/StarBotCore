package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * StarBot 监听外部事件触发事件处理
 */
@Slf4j
@Order(0)
@Component
public class StarBotHandlerListener implements ApplicationListener<StarBotExternalBaseEvent> {
    @Resource
    private AbstractDataSource dataSource;

    @Resource
    private StarBotEventHandlerService handlerService;

    @Override
    public void onApplicationEvent(StarBotExternalBaseEvent event) {
        Optional<PushUser> optionalUser = dataSource.getUser(event.getPlatform(), event.getSource().getUid());
        if (optionalUser.isEmpty()) {
            return;
        }

        String eventClass = event.getClass().getName();

        PushUser user = optionalUser.get();
        for (PushTarget target : user.getTargets()) {
            for (PushMessage message : target.getMessages()) {
                if (eventClass.equals(message.getEvent())) {
                    Optional<StarBotEventHandler> optionalHandler = handlerService.getHandler(message.getEvent(), message.getHandler());

                    optionalHandler.ifPresentOrElse(
                            handler -> {
                                try {
                                    handler.handle(event, message);
                                } catch (Exception e) {
                                    log.error("事件处理器 {} 处理事件 {} 异常", handler.getClass().getName(), eventClass, e);
                                }
                            },
                            () -> log.error("未找到事件 {} 的处理器, 请检查推送配置是否正确", message.getEvent())
                    );
                }
            }
        }
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }
}
