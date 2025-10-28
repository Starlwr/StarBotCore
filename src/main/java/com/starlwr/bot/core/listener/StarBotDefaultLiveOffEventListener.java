package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.service.LiveDataService;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 下播事件监听器
 */
@Slf4j
@Component
public class StarBotDefaultLiveOffEventListener {
    @Resource
    private LiveDataService liveDataService;

    /**
     * 更新房间数据
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onApplicationEvent(@NonNull LiveOffEvent event) {
        log.info("[{}] [下播] {}(UID: {}, 房间号: {})", event.getPlatform(), event.getSource().getUname(), event.getSource().getUid(), event.getSource().getRoomIdString());

        liveDataService.setLiveStatus(event.getPlatform(), event.getSource().getUid(), false);
        liveDataService.setLiveEndTime(event.getPlatform(), event.getSource().getUid(), event.getTimestamp());
    }
}
