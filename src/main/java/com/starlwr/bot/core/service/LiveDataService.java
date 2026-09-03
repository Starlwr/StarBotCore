package com.starlwr.bot.core.service;

import com.starlwr.bot.core.event.live.common.*;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;

/**
 * 直播数据服务接口
 */
public interface LiveDataService {
    /**
     * 设置直播间状态
     * @param platform 直播平台
     * @param uid UID
     * @param status 直播间状态
     */
    void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status);

    /**
     * 获取直播间状态
     * @param platform 直播平台
     * @param uid UID
     * @return 直播间状态
     */
    Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置最近一场直播开始时间戳
     * @param platform 直播平台
     * @param uid UID
     * @param startTime 最近一场直播开始时间戳
     */
    void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime);

    /**
     * 获取最近一场直播开始时间戳
     * @param platform 直播平台
     * @param uid UID
     * @return 最近一场直播开始时间戳
     */
    Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     * @param endTime 最近一场直播结束时间戳
     */
    void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime);

    /**
     * 获取最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     * @return 最近一场直播结束时间戳
     */
    Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 删除最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     */
    void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 重置最近一场直播数据
     * @param platform 直播平台
     * @param uid UID
     */
    void resetLiveData(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置自定义对象
     * @param value 对象
     * @param keys 多级键
     */
    void setCustomObject(@NonNull Object value, @NonNull String... keys);

    /**
     * 获取自定义对象
     * @param type 目标类型
     * @param keys 多级键
     * @return 自定义对象
     */
    <T> Optional<T> getCustomObject(Class<T> type, @NonNull String... keys);

    /**
     * 删除自定义对象
     * @param keys 多级键
     * @return 是否删除成功
     */
    boolean deleteCustomObject(@NonNull String... keys);

    /**
     * 添加弹幕记录
     * @param event 弹幕事件
     */
    void addDanmu(@NonNull DanmuEvent event);

    /**
     * 获取最近一场直播弹幕列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播弹幕列表
     */
    <T> List<T> getDanmu(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的弹幕列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的弹幕列表
     */
    <T> List<T> getUserDanmu(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加表情弹幕记录
     * @param event 表情事件
     */
    void addEmoji(@NonNull EmojiEvent event);

    /**
     * 获取最近一场直播表情弹幕列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播表情弹幕列表
     */
    <T> List<T> getEmoji(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的表情弹幕列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的表情弹幕列表
     */
    <T> List<T> getUserEmoji(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加免费礼物记录
     * @param event 免费礼物事件
     */
    void addFreeGift(@NonNull FreeGiftEvent event);

    /**
     * 获取最近一场直播免费礼物列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播免费礼物列表
     */
    <T> List<T> getFreeGift(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的免费礼物列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的免费礼物列表
     */
    <T> List<T> getUserFreeGift(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加付费礼物记录
     * @param event 付费礼物事件
     */
    void addPaidGift(@NonNull PaidGiftEvent event);

    /**
     * 获取最近一场直播付费礼物列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播付费礼物列表
     */
    <T> List<T> getPaidGift(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的付费礼物列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的付费礼物列表
     */
    <T> List<T> getUserPaidGift(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加随机礼物记录
     * @param event 随机礼物事件
     */
    void addRandomGift(@NonNull RandomGiftEvent event);

    /**
     * 获取最近一场直播随机礼物列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播随机礼物列表
     */
    <T> List<T> getRandomGift(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的随机礼物列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的随机礼物列表
     */
    <T> List<T> getUserRandomGift(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加醒目留言记录
     * @param event 醒目留言事件
     */
    void addSuperChat(@NonNull SuperChatEvent event);

    /**
     * 获取最近一场直播醒目留言列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播醒目留言列表
     */
    <T> List<T> getSuperChat(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的醒目留言列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的醒目留言列表
     */
    <T> List<T> getUserSuperChat(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加开通会员记录
     * @param event 开通会员事件
     */
    void addMemberShip(@NonNull MembershipEvent event);

    /**
     * 获取最近一场直播开通会员列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播开通会员列表
     */
    <T> List<T> getMemberShip(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的开通会员列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的开通会员列表
     */
    <T> List<T> getUserMemberShip(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加进入房间记录
     * @param event 进入房间事件
     */
    void addEnterRoom(@NonNull EnterRoomEvent event);

    /**
     * 获取最近一场直播进入房间记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播进入房间记录列表
     */
    <T> List<T> getEnterRoom(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的进入房间记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的进入房间记录列表
     */
    <T> List<T> getUserEnterRoom(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加关注记录
     * @param event 关注事件
     */
    void addFollow(@NonNull FollowEvent event);

    /**
     * 获取最近一场直播关注记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播关注记录列表
     */
    <T> List<T> getFollow(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的关注记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的关注记录列表
     */
    <T> List<T> getUserFollow(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加点赞记录
     * @param event 点赞事件
     */
    void addLike(@NonNull LikeEvent event);

    /**
     * 获取最近一场直播点赞记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播点赞记录列表
     */
    <T> List<T> getLike(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的点赞记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的点赞记录列表
     */
    <T> List<T> getUserLike(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);

    /**
     * 添加分享记录
     * @param event 分享事件
     */
    void addShare(@NonNull ShareEvent event);

    /**
     * 获取最近一场直播分享记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param type 目标类型
     * @return 最近一场直播分享记录列表
     */
    <T> List<T> getShare(@NonNull String platform, @NonNull Long uid, @NonNull Class<T> type);

    /**
     * 获取最近一场直播指定观众的分享记录列表
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param senderUid 观众 UID
     * @param type 目标类型
     * @return 最近一场直播指定观众的分享记录列表
     */
    <T> List<T> getUserShare(@NonNull String platform, @NonNull Long uid, @NonNull Long senderUid, @NonNull Class<T> type);
}
