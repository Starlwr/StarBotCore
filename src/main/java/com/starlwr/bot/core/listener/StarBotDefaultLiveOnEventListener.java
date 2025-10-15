package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.service.LiveDataService;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 开播事件监听器
 */
@Slf4j
@Component
@Order(-10000)
public class StarBotDefaultLiveOnEventListener implements ApplicationListener<LiveOnEvent> {
    @Resource
    private LiveDataService liveDataService;

    @Override
    public void onApplicationEvent(@NonNull LiveOnEvent event) {
        log.info("[{}] [开播] {}(UID: {}, 房间号: {})", event.getPlatform(), event.getSource().getUname(), event.getSource().getUid(), event.getSource().getRoomIdString());

        liveDataService.setLiveStatus(event.getPlatform(), event.getSource().getUid(), true);
        liveDataService.setLiveStartTime(event.getPlatform(), event.getSource().getUid(), event.getTimestamp());
        liveDataService.resetLiveData(event.getPlatform(), event.getSource().getUid());
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }
}
