package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.event.live.base.StarBotLiveUserEvent;
import com.starlwr.bot.core.event.live.common.*;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 用户事件监听器
 */
@Slf4j
@Component
public class StarBotDefaultLiveUserEventListener {
    private final LiveDataService liveDataService;

    public StarBotDefaultLiveUserEventListener(LiveDataService liveDataService) {
        this.liveDataService = liveDataService;
    }

    /**
     * 直播数据存储
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onUserEvent(StarBotLiveUserEvent event) {
        if (event instanceof DanmuEvent) {
            liveDataService.addDanmu((DanmuEvent) event);
        } else if (event instanceof EmojiEvent) {
            liveDataService.addEmoji((EmojiEvent) event);
        } else if (event instanceof FreeGiftEvent) {
            liveDataService.addFreeGift((FreeGiftEvent) event);
        } else if (event instanceof PaidGiftEvent) {
            liveDataService.addPaidGift((PaidGiftEvent) event);
        } else if (event instanceof RandomGiftEvent) {
            liveDataService.addRandomGift((RandomGiftEvent) event);
        } else if (event instanceof SuperChatEvent) {
            liveDataService.addSuperChat((SuperChatEvent) event);
        } else if (event instanceof MembershipEvent) {
            liveDataService.addMemberShip((MembershipEvent) event);
        } else if (event instanceof EnterRoomEvent) {
            liveDataService.addEnterRoom((EnterRoomEvent) event);
        } else if (event instanceof FollowEvent) {
            liveDataService.addFollow((FollowEvent) event);
        } else if (event instanceof LikeEvent) {
            liveDataService.addLike((LikeEvent) event);
        } else if (event instanceof ShareEvent) {
            liveDataService.addShare((ShareEvent) event);
        }
    }
}
