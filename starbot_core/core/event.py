from dataclasses import dataclass
from enum import Enum
from typing import Optional


class EventType:
    """
    事件类型枚举，基于本项目开发时，可通过继承此类的方式扩展事件类型
    """
    LiveEvent = "LiveEvent"
    """直播间事件"""


class LiveEvent(Enum):
    """
    直播间事件类型枚举
    """
    ConnectedEvent = "ConnectedEvent"
    """连接成功"""

    DisconnectedEvent = "DisconnectedEvent"
    """断开连接"""

    TimeoutEvent = "TimeoutEvent"
    """心跳响应超时"""

    LiveOnEvent = "LiveOnEvent"
    """开播"""

    LiveOffEvent = "LiveOffEvent"
    """下播"""

    DanmuEvent = "DanmuEvent"
    """文字弹幕"""

    EmojiEvent = "EmojiEvent"
    """大表情弹幕"""

    SilverGiftEvent = "SilverGiftEvent"
    """免费礼物"""

    GoldGiftEvent = "GoldGiftEvent"
    """付费礼物"""

    BlindGiftEvent = "BlindGiftEvent"
    """盲盒礼物"""

    SuperChatEvent = "SuperChatEvent"
    """醒目留言"""

    CaptainEvent = "CaptainEvent"
    """开通舰长"""

    CommanderEvent = "CommanderEvent"
    """开通提督"""

    GovernorEvent = "GovernorEvent"
    """开通总督"""

    EnterRoomEvent = "EnterRoomEvent"
    """进房"""

    FollowEvent = "FollowEvent"
    """关注"""

    WatchedUpdateEvent = "WatchedUpdateEvent"
    """观看过人数更新"""

    LikeClickEvent = "LikeClickEvent"
    """点赞"""

    LikeUpdateEvent = "LikeUpdateEvent"
    """点赞数更新"""


@dataclass
class UserInfo:
    """
    用户信息类
    """
    face: Optional[str]
    """头像"""

    uid: int
    """UID"""

    uname: str
    """昵称"""


@dataclass
class RoomUserInfo(UserInfo):
    """
    含直播间用户信息类
    """
    room_id: int
    """房间号"""


@dataclass
class FansMedalInfo(RoomUserInfo):
    """
    粉丝勋章信息类（部分事件中粉丝勋章信息不完整）
    """
    name: str
    """名称"""

    level: int
    """等级"""


@dataclass
class RoomInfo:
    """
    直播间信息类
    """
    title: str
    """直播间标题"""

    cover: str
    """直播间封面链接"""

    parent_area: str
    """一级分区名称"""

    area: str
    """二级分区名称"""

    fans_count: int
    """粉丝数量"""

    fans_medal_count: int
    """粉丝团数量"""

    guard_count: int
    """大航海数量"""


@dataclass
class BaseLiveEvent:
    """
    直播间事件基类
    """
    source: RoomUserInfo
    """来源直播间主播信息"""

    sender: UserInfo
    """事件触发者信息"""


@dataclass
class ConnectedEvent:
    """
    连接成功事件
    """
    source: RoomUserInfo
    """来源直播间主播信息"""


@dataclass
class DisconnectedEvent:
    """
    断开连接事件
    """
    source: RoomUserInfo
    """来源直播间主播信息"""


@dataclass
class TimeoutEvent:
    """
    心跳响应超时事件
    """
    source: RoomUserInfo
    """来源直播间主播信息"""


@dataclass
class LiveOnEvent:
    """
    开播事件
    """
    source: RoomUserInfo
    """来源直播间主播信息"""

    info: RoomInfo
    """来源直播间信息"""

    timestamp: int
    """开播时间戳"""


@dataclass
class LiveOffEvent:
    """
    下播事件
    """
    source: RoomUserInfo
    """来源直播间主播信息"""

    info: RoomInfo
    """来源直播间信息"""


@dataclass
class DanmuEvent(BaseLiveEvent):
    """
    文字弹幕事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""

    honor_level: int
    """荣耀等级"""

    content: str
    """弹幕内容"""


@dataclass
class EmojiEvent(BaseLiveEvent):
    """
    大表情弹幕事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""

    honor_level: int
    """荣耀等级"""

    id: str
    """大表情唯一标识"""

    name: str
    """大表情名称"""

    width: int
    """宽度"""

    height: int
    """高度"""

    url: str
    """图片链接"""


@dataclass
class SilverGiftEvent(BaseLiveEvent):
    """
    免费礼物事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""

    honor_level: int
    """荣耀等级"""

    id: int
    """礼物 ID"""

    name: str
    """礼物名称"""

    count: int
    """数量"""


@dataclass
class GoldGiftEvent(BaseLiveEvent):
    """
    付费礼物事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""

    honor_level: int
    """荣耀等级"""

    id: int
    """礼物 ID"""

    name: str
    """名称"""

    count: int
    """数量"""

    price: float
    """价格（元）"""


@dataclass
class BlindGiftEvent(BaseLiveEvent):
    """
    盲盒礼物事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""

    honor_level: int
    """荣耀等级"""

    blind_id: int
    """盲盒 ID"""

    gift_id: int
    """爆出礼物 ID"""

    blind_name: str
    """盲盒名称"""

    gift_name: str
    """爆出礼物名称"""

    count: int
    """数量"""

    price: float
    """价格（元）"""

    value: float
    """爆出礼物价值（元）"""

    profit: float
    """盈亏（元）"""


@dataclass
class SuperChatEvent(BaseLiveEvent):
    """
    醒目留言事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""

    content: str
    """内容"""

    price: int
    """价格（元）"""


@dataclass
class CaptainEvent(BaseLiveEvent):
    """
    开通舰长事件
    """
    month: int
    """开通月数"""


@dataclass
class CommanderEvent(BaseLiveEvent):
    """
    开通提督事件
    """
    month: int
    """开通月数"""


@dataclass
class GovernorEvent(BaseLiveEvent):
    """
    开通总督事件
    """
    month: int
    """开通月数"""


@dataclass
class EnterRoomEvent(BaseLiveEvent):
    """
    进房事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""


@dataclass
class FollowEvent(BaseLiveEvent):
    """
    关注事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""


@dataclass
class WatchedUpdateEvent:
    """
    观看过人数更新事件
    """
    source: RoomUserInfo
    """来源直播间主播信息"""

    count: int
    """观看过人数"""


@dataclass
class LikeClickEvent(BaseLiveEvent):
    """
    点赞事件
    """
    fans_medal: Optional[FansMedalInfo]
    """粉丝勋章信息"""

    icon: str
    """图标"""


@dataclass
class LikeUpdateEvent:
    """
    点赞数更新事件
    """
    source: RoomUserInfo
    """来源直播间主播信息"""

    count: int
    """点赞数"""
