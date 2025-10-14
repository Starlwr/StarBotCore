package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.service.LiveDataService;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 下播事件监听器
 */
@Slf4j
@Component
@Order(-10000)
public class StarBotDefaultLiveOffEventListener implements ApplicationListener<LiveOffEvent> {
    @Resource
    private LiveDataService liveDataService;

    @Override
    public void onApplicationEvent(@NonNull LiveOffEvent event) {
        liveDataService.setLiveStatus(event.getPlatform(), event.getSource().getUid(), false);
        liveDataService.setLiveEndTime(event.getPlatform(), event.getSource().getUid(), event.getTimestamp());
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }
}
