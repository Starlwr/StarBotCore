import asyncio
import base64
import json
import struct
from asyncio import Task
from decimal import Decimal
from typing import NoReturn, Dict, Any, List, Union, Tuple

import aiohttp
import brotli
from aiohttp import ClientWebSocketResponse, WSMessage, ClientConnectorError
from loguru import logger
from starbot_executor import executor

from .event import *
from ..exception.LiveException import LiveException
from ..exception.LiveListenerException import LiveListenerException
from ..utils.network import request, get_session, credential


class ConnectionStatus(Enum):
    """
    连接状态枚举
    """
    INIT = 0
    CONNECTING = 1
    CONNECTED = 2
    CLOSING = 3
    CLOSED = 4
    TIMEOUT = 5
    ERROR = 6


class DataHeaderType(Enum):
    """
    数据包头枚举
    """
    RAW_JSON = 0
    HEARTBEAT = 1
    BROTLI_JSON = 3


class DataPackType(Enum):
    """
    数据包类型枚举
    """
    HEARTBEAT = 2
    HEARTBEAT_RESPONSE = 3
    NOTICE = 5
    VERIFY = 7
    VERIFY_SUCCESS_RESPONSE = 8


class LiveRoom:
    """
    直播间类
    """
    uid: int
    """主播 UID，与主播直播间房间号任选其一传入，另一参数会自动获取"""

    room_id: int
    """主播直播间房间号，与主播 UID 任选其一传入，另一参数会自动获取"""

    uname: str
    """主播昵称，无需手动传入，会自动获取"""

    face: str
    """主播头像链接，无需手动传入，会自动获取"""

    status: ConnectionStatus
    """连接状态"""

    __ws: Optional[ClientWebSocketResponse]
    """直播间 websocket 连接"""

    __heartbeat_task: Optional[Task]
    """直播间心跳任务"""

    __heartbeat_timer: int
    """直播间心跳任务计时器"""

    __interval: Union[int, float]
    """连接断开后重新连接间隔时长"""

    @classmethod
    async def create(cls,
                     uid: Optional[int] = None,
                     room_id: Optional[int] = None,
                     interval: Union[int, float] = 5) -> "LiveRoom":
        """
        创建 LiveRoom 实例，主播 UID 与主播直播间房间号任选其一传入，另一参数会自动获取

        Args:
            uid: 主播 UID，与主播直播间房间号任选其一传入，另一参数会自动获取
            room_id: 主播直播间房间号，与主播 UID 任选其一传入，另一参数会自动获取
            interval: 连接断开后重新连接间隔时长

        Returns:
            LiveRoom 实例
        """
        if uid is None and room_id is None:
            raise ValueError(f"uid 和 room_id 参数需至少传入一个")

        self = LiveRoom()
        self.status = ConnectionStatus.INIT

        if uid is not None:
            self.uid = uid
            user_info = await self.get_user_info()
            self.uname = user_info["info"]["uname"]
            self.face = user_info["info"]["face"]
            if user_info["room_id"] == 0:
                raise LiveException(f"UP 主 {self.uname} ( UID: {self.uid} ) 还未开通直播间")
            self.room_id = user_info["room_id"]
        else:
            if room_id == 0:
                raise LiveException(f"未开通直播间")
            self.room_id = room_id
            room_info = await self.get_room_info()
            self.room_id = room_info["room_info"]["room_id"]
            self.uid = room_info["room_info"]["uid"]
            self.uname = room_info["anchor_info"]["base_info"]["uname"]
            self.face = room_info["anchor_info"]["base_info"]["face"]

        self.__ws = None
        self.__heartbeat_task = None
        self.__heartbeat_timer = 0
        self.__interval = interval
        self.__add_event_listener()

        return self

    @staticmethod
    async def query_user_info(uid: int) -> Tuple[str, int, str]:
        """
        根据 UID 查询用户信息

        Args:
            uid: 要查询的用户 UID

        Returns:
            用户信息元组，格式为：(昵称, 直播间房间号, 头像)
        """
        room = LiveRoom()
        room.uid = uid
        info = await room.get_user_info()
        return info["info"]["uname"], info["room_id"], info["info"]["face"]

    async def get_user_info(self) -> Dict[str, Any]:
        """
        获取主播信息

        Returns:
            主播信息
        """
        method = "GET"
        url = "https://api.live.bilibili.com/live_user/v1/Master/info"
        params = {
            "uid": self.uid
        }
        return await request(method, url, params=params)

    @staticmethod
    async def query_room_base_info(room_id: int) -> Tuple[int, int, str, str, str, str, int, int, int]:
        """
        根据房间号查询直播间基础信息

        Args:
            room_id: 要查询的房间号

        Returns:
            基础信息元组，格式为：(直播间状态，开播时间戳，直播间标题，直播间封面链接，一级分区名称，二级分区名称，粉丝数量，粉丝团数量，大航海数量)
        """
        room = LiveRoom()
        room.room_id = room_id
        info = await room.get_room_info()
        base_room_info = info["room_info"]
        status = base_room_info["live_status"]
        start_time = base_room_info["live_start_time"]
        title = base_room_info["title"]
        cover = base_room_info["cover"]
        parent_area = base_room_info["parent_area_name"]
        area = base_room_info["area_name"]
        anchor_info = info["anchor_info"]
        fans_count = anchor_info["relation_info"]["attention"]
        if anchor_info["medal_info"] is None:
            fans_medal_count = 0
        else:
            fans_medal_count = anchor_info["medal_info"]["fansclub"]
        guard_count = info["guard_info"]["count"]

        return status, start_time, title, cover, parent_area, area, fans_count, fans_medal_count, guard_count

    async def get_room_info(self) -> Dict[str, Any]:
        """
        获取直播间信息

        Returns:
            直播间信息
        """
        method = "GET"
        url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom"
        params = {
            "room_id": self.room_id
        }
        return await request(method, url, params=params)

    async def get_chat_conf(self) -> Dict[str, Any]:
        """
        获取直播服务器配置

        Returns:
            直播服务器配置
        """
        method = "GET"
        url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
        params = {
            "id": self.room_id
        }
        return await request(method, url, params=params)

    async def connect(self) -> NoReturn:
        """
        连接直播间
        """
        if self.status in (ConnectionStatus.CONNECTING, ConnectionStatus.CONNECTED, ConnectionStatus.CLOSING):
            return

        self.status = ConnectionStatus.CONNECTING

        available_hosts = None
        host = None
        session = get_session()

        while True:
            received = False

            if not available_hosts:
                conf = await self.get_chat_conf()
                available_hosts = conf["host_list"]

            if host is None:
                host = available_hosts.pop()

            port = host["wss_port"]
            uri = f"wss://{host['host']}:{port}/sub"

            try:
                async with session.ws_connect(uri, headers={"User-Agent": "Mozilla/5.0"}) as ws:
                    self.__ws = ws
                    await self.__send_verify_data(ws, conf["token"])

                    msg: WSMessage
                    async for msg in ws:
                        if msg.type == aiohttp.WSMsgType.BINARY:
                            received = True
                            await self.__handle_data(msg.data)
                        elif msg.type == aiohttp.WSMsgType.ERROR:
                            logger.error(f"直播间 {self.room_id} 出现错误")
                            self.status = ConnectionStatus.ERROR
                        elif msg.type == aiohttp.WSMsgType.CLOSING:
                            self.status = ConnectionStatus.CLOSING
                        elif msg.type == aiohttp.WSMsgType.CLOSED:
                            self.status = ConnectionStatus.CLOSED

                    if self.status in (ConnectionStatus.CLOSING, ConnectionStatus.CLOSED):
                        break
            except ClientConnectorError:
                continue
            finally:
                if not received:
                    host = None

    async def disconnect(self) -> NoReturn:
        """
        断开直播间
        """
        if self.status != ConnectionStatus.CONNECTED:
            return

        self.status = ConnectionStatus.CLOSING

        if self.__heartbeat_task is not None:
            self.__heartbeat_task.cancel()
        if self.__ws is not None:
            await self.__ws.close()

        self.status = ConnectionStatus.CLOSED

        source = RoomUserInfo(self.face, self.uid, self.uname, self.room_id)
        event = DisconnectedEvent(source)
        executor.dispatch(event, EventType.LiveEvent, LiveEvent.DisconnectedEvent, self.room_id)

    def __add_event_listener(self) -> NoReturn:
        """
        添加心跳响应超时和连接成功监听器
        """
        executor.remove_event_listener("CONNECTED", self.room_id, channel="RAW")
        executor.remove_event_listener("TIMEOUT", self.room_id, channel="RAW")

        @executor.on("CONNECTED", self.room_id, channel="RAW")
        async def on_connected():
            self.__heartbeat_timer = 0
            self.__heartbeat_task = executor.create_task(self.__heartbeat(self.__ws))
            source = RoomUserInfo(self.face, self.uid, self.uname, self.room_id)
            event = ConnectedEvent(source)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.ConnectedEvent, self.room_id)

        @executor.on("TIMEOUT", self.room_id, channel="RAW")
        async def on_timeout():
            source = RoomUserInfo(self.face, self.uid, self.uname, self.room_id)
            event = TimeoutEvent(source)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.TimeoutEvent, self.room_id)

            logger.warning(f"直播间 {self.room_id} 连接异常: 心跳响应超时")
            await self.disconnect()
            logger.warning(f"即将重新连接直播间 {self.room_id} ...")
            await executor.create_queue_task(self.connect, self.__interval)

    async def __send_verify_data(self, ws: ClientWebSocketResponse, token: str) -> NoReturn:
        """
        发送认证信息

        Args:
            ws: WebSocket 实例
            token: 连接凭证
        """
        uid = credential.login_uid
        if uid == 0:
            logger.warning("未登录 B 站账号, 将无法抓取到完整数据, 建议先登录账号再进行抓取")

        verify_data = {"uid": uid, "roomid": self.room_id, "protover": 3, "buvid": credential.buvid3,
                       "platform": "web", "type": 2, "key": token}
        data = json.dumps(verify_data).encode()
        await self.__send(data, DataHeaderType.HEARTBEAT, DataPackType.VERIFY, ws)

    async def __handle_data(self, data) -> NoReturn:
        """
        处理数据

        Args:
            data: 原始数据
        """
        data = self.__unpack(data)

        for info in data:
            callback_info = {
                "room_id": self.room_id
            }
            if info["datapack_type"] == DataPackType.VERIFY_SUCCESS_RESPONSE.value:
                if info["data"]["code"] == 0:
                    self.status = ConnectionStatus.CONNECTED
                    callback_info["type"] = "CONNECTED"
                    callback_info["data"] = None
                    executor.dispatch(callback_info, "CONNECTED", self.room_id, channel="RAW")

            elif info["datapack_type"] == DataPackType.HEARTBEAT_RESPONSE.value:
                self.__heartbeat_timer = 30
                callback_info["type"] = "VIEW"
                callback_info["data"] = info["data"]["view"]
                executor.dispatch(callback_info, "VIEW", self.room_id, channel="RAW")

            elif info["datapack_type"] == DataPackType.NOTICE.value:
                callback_info["type"] = info["data"]["cmd"]
                if callback_info["type"].find("DANMU_MSG") > -1:
                    callback_info["type"] = "DANMU_MSG"
                    info["data"]["cmd"] = "DANMU_MSG"
                callback_info["data"] = info["data"]
                executor.dispatch(callback_info, callback_info["type"], self.room_id, channel="RAW")
            else:
                logger.warning(f"直播间 {self.room_id} 检测到未知的数据包类型, 无法处理")

    async def __heartbeat(self, ws: ClientWebSocketResponse) -> NoReturn:
        """
        心跳包发送任务

        Args:
            ws: WebSocket 实例
        """
        heartbeat = self.__pack(b"[object Object]", DataHeaderType.HEARTBEAT, DataPackType.HEARTBEAT)
        heartbeat_url = "https://live-trace.bilibili.com/xlive/rdata-interface/v1/heartbeat/webHeartBeat?pf=web&hb="
        hb = str(base64.b64encode(f"60|{self.room_id}|1|0".encode("utf-8")), "utf-8")
        while True:
            if self.__heartbeat_timer == 0 or self.__heartbeat_timer == -15:
                await ws.send_bytes(heartbeat)
                try:
                    await request("GET", heartbeat_url, {"hb": hb, "pf": "web"})
                except Exception:
                    pass
            elif self.__heartbeat_timer <= -30:
                self.status = ConnectionStatus.ERROR
                executor.dispatch(None, "TIMEOUT", self.room_id, channel="RAW")
                break

            await asyncio.sleep(1.0)
            self.__heartbeat_timer -= 1

    async def __send(self, data: bytes, header_type: DataHeaderType, pack_type: DataPackType,
                     ws: ClientWebSocketResponse) -> NoReturn:
        """
        自动打包并发送数据

        Args:
            data: 要发送的数据
            header_type: 包头类型
            pack_type: 数据打包类型
            ws: WebSocket 实例
        """
        data = self.__pack(data, header_type, pack_type)
        await ws.send_bytes(data)

    @staticmethod
    def __pack(data: bytes, header_type: DataHeaderType, pack_type: DataPackType) -> bytes:
        """
        打包数据

        Args:
            data: 要打包的数据
            header_type: 包头类型
            pack_type: 数据打包类型

        Returns:
            打包后的数据
        """
        send_data = bytearray()
        send_data += struct.pack(">H", 16)
        assert 0 <= header_type.value <= 2, LiveException("数据包协议版本错误, 范围 0 ~ 2")
        send_data += struct.pack(">H", header_type.value)
        assert pack_type.value in [2, 7], LiveException("数据包类型错误, 可用类型：2, 7")
        send_data += struct.pack(">I", pack_type.value)
        send_data += struct.pack(">I", 1)
        send_data += data
        send_data = struct.pack(">I", len(send_data) + 4) + send_data
        return bytes(send_data)

    @staticmethod
    def __unpack(data: bytes) -> List[Dict[str, Any]]:
        """
        解包数据

        Args:
            data: 要解包的数据

        Returns:
            解包后的数据
        """
        ret = []
        offset = 0
        header = struct.unpack(">IHHII", data[:16])
        if header[2] == DataHeaderType.BROTLI_JSON.value:
            real_data = brotli.decompress(data[16:])
        else:
            real_data = data

        if header[2] == DataHeaderType.HEARTBEAT.value and header[3] == DataPackType.HEARTBEAT_RESPONSE.value:
            real_data = real_data[16:]
            # 心跳包协议特殊处理
            recv_data = {
                "protocol_version": header[2],
                "datapack_type": header[3],
                "data": {
                    "view": struct.unpack(">I", real_data[0:4])[0]
                }
            }
            ret.append(recv_data)
            return ret

        while offset < len(real_data):
            header = struct.unpack(">IHHII", real_data[offset:offset + 16])
            length = header[0]
            recv_data = {
                "protocol_version": header[2],
                "datapack_type": header[3],
                "data": None
            }
            chunk_data = real_data[(offset + 16):(offset + length)]
            if header[2] == 0:
                recv_data["data"] = json.loads(chunk_data.decode())
            elif header[2] == 2:
                recv_data["data"] = json.loads(chunk_data.decode())
            elif header[2] == 1:
                if header[3] == DataPackType.HEARTBEAT_RESPONSE.value:
                    recv_data["data"] = {
                        "view": struct.unpack(">I", chunk_data)[0]}
                elif header[3] == DataPackType.VERIFY_SUCCESS_RESPONSE.value:
                    recv_data["data"] = json.loads(chunk_data.decode())
            ret.append(recv_data)
            offset += length
        return ret


class LiveEventListener:
    """
    直播间事件监听器
    """
    interval: Union[int, float]
    """
    连接和重连每个直播间的间隔时长，用于避免连接大量直播间时被风控或导致并发过多异常 too many file descriptors in select()，单位：秒
    当连接的直播间较多时，建议设置为 5 以上的数值，否则可能会被风控导致无法抓取到完整的数据
    """

    auto_complete: bool = False
    """是否自动补全事件中缺失的信息，开启后可能会因网络请求耗时导致事件延迟发布"""

    __rooms: Dict[int, LiveRoom]
    """监听中的直播间"""

    def __init__(self, interval: Union[int, float] = 5, auto_complete: bool = False):
        self.interval = interval
        self.auto_complete = auto_complete
        self.__rooms = {}

    def __contains__(self, item):
        return item in self.__rooms

    async def add(self, uid: Optional[int] = None, room_id: Optional[int] = None) -> int:
        """
        添加监听直播间

        Args:
            uid: 主播 UID，与主播直播间房间号任选其一传入，另一参数会自动获取
            room_id: 主播直播间房间号，与主播 UID 任选其一传入，另一参数会自动获取

        Returns:
            主播 UID，可用于调用 remove 方法时使用
        """
        if uid is not None:
            room = await LiveRoom.create(uid=uid, interval=self.interval)
        else:
            room = await LiveRoom.create(room_id=room_id, interval=self.interval)

        if room.uid in self.__rooms:
            raise LiveListenerException(f"{room.uname} 的直播间 {room.room_id} 已添加监听, 不可重复添加")

        self.__rooms[room.uid] = room
        await executor.create_queue_task(room.connect, self.interval)

        source = RoomUserInfo(room.face, room.uid, room.uname, room.room_id)

        @executor.on("LIVE", room.room_id, channel="RAW")
        async def live_on(raw):
            """
            原始开播事件

            发布事件:
                - :class:`LiveOnEvent`: 开播事件
            """
            if "live_time" not in raw["data"]:
                return

            (
                _, start_time, title, cover, parent_area, area, fans_count, fans_medal_count, guard_count
            ) = await room.query_room_base_info(room.room_id)
            info = RoomInfo(title, cover, parent_area, area, fans_count, fans_medal_count, guard_count)

            event = LiveOnEvent(source, info, start_time)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.LiveOnEvent, room.room_id)

        @executor.on("PREPARING", room.room_id, channel="RAW")
        async def live_off():
            """
            原始下播事件

            发布事件:
                - :class:`LiveOffEvent`: 下播事件
            """
            (
                _, _, title, cover, parent_area, area, fans_count, fans_medal_count, guard_count
            ) = await room.query_room_base_info(room.room_id)
            info = RoomInfo(title, cover, parent_area, area, fans_count, fans_medal_count, guard_count)

            event = LiveOffEvent(source, info)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.LiveOffEvent, room.room_id)

        @executor.on("DANMU_MSG", room.room_id, channel="RAW")
        async def on_danmu(raw):
            """
            原始弹幕事件

            发布事件:
                - :class:`DanmuEvent`: 文字弹幕事件
                - :class:`EmojiEvent`: 大表情弹幕事件
            """
            base = raw["data"]["info"]
            danmu_uid, danmu_uname = base[2][:2]
            sender = UserInfo("", danmu_uid, danmu_uname)
            if self.auto_complete:
                _, _, sender.face = await LiveRoom.query_user_info(danmu_uid)

            fans_medal = None
            if base[3]:
                fans_medal_level, fans_medal_name, fans_medal_uname, fans_medal_room_id = base[3][:4]
                fans_medal_uid = base[3][12]
                fans_medal = FansMedalInfo(
                    "", fans_medal_uid, fans_medal_uname, fans_medal_room_id, fans_medal_name, fans_medal_level
                )
                if self.auto_complete:
                    _, _, fans_medal.face = await LiveRoom.query_user_info(fans_medal_uid)

            honor_level = base[16][0]

            if isinstance(base[0][13], str):
                content = base[1]
                event = DanmuEvent(source, sender, fans_medal, honor_level, content)
                event_type = LiveEvent.DanmuEvent
            else:
                emoji_data = base[0][13]
                emoji_id, name, width, height, url = (
                    emoji_data["emoticon_unique"], base[1], emoji_data["width"], emoji_data["height"], emoji_data["url"]
                )
                event = EmojiEvent(source, sender, fans_medal, honor_level, emoji_id, name, width, height, url)
                event_type = LiveEvent.EmojiEvent

            executor.dispatch(event, EventType.LiveEvent, event_type, room.room_id)

        @executor.on("SEND_GIFT", room.room_id, channel="RAW")
        async def on_gift(raw):
            """
            原始礼物事件

            发布事件:
                - :class:`SilverGiftEvent`: 免费礼物事件
                - :class:`GoldGiftEvent`: 付费礼物事件
                - :class:`BlindGiftEvent`: 盲盒礼物事件
            """
            base = raw["data"]["data"]
            gift_uid, gift_uname, gift_face = base["uid"], base["uname"], base["face"]
            sender = UserInfo(gift_face, gift_uid, gift_uname)

            fans_medal = None
            medal_info = base["medal_info"]
            if medal_info["target_id"]:
                (
                    fans_medal_level, fans_medal_name,
                    fans_medal_uid, fans_medal_uname, fans_medal_room_id, fans_medal_face
                ) = (
                    medal_info["medal_level"], medal_info["medal_name"],
                    medal_info["target_id"], "", 0, ""
                )

                if self.auto_complete:
                    fans_medal_uname, fans_medal_room_id, fans_medal_face = await LiveRoom.query_user_info(
                        fans_medal_uid
                    )

                fans_medal = FansMedalInfo(
                    fans_medal_face, fans_medal_uid, fans_medal_uname, fans_medal_room_id,
                    fans_medal_name, fans_medal_level
                )

            honor_level = base["wealth_level"]

            gift_id, gift_name, count, total_coin, discount_price = (
                base["giftId"], base["giftName"], base["num"], base["total_coin"], base["discount_price"]
            )
            price_decimal = Decimal(discount_price) / Decimal(1000) * Decimal(count)
            # 幸运之钥主播收益为 1%
            if gift_id == 31709:
                price_decimal = price_decimal * Decimal("0.01")
            price = float(price_decimal)

            if total_coin == 0 or discount_price == 0:
                event = SilverGiftEvent(source, sender, fans_medal, honor_level, gift_id, gift_name, count)
                executor.dispatch(event, EventType.LiveEvent, LiveEvent.SilverGiftEvent, room.room_id)
                return

            if base["blind_gift"] is None:
                event = GoldGiftEvent(source, sender, fans_medal, honor_level, gift_id, gift_name, count, price)
                executor.dispatch(event, EventType.LiveEvent, LiveEvent.GoldGiftEvent, room.room_id)
                return

            blind_gift_price_decimal = Decimal(total_coin) / Decimal(1000)
            blind_gift_price = float(blind_gift_price_decimal)
            profit = float(price_decimal - blind_gift_price_decimal)
            blind_gift_data = base["blind_gift"]
            blind_gift_id, blind_gift_name = blind_gift_data["original_gift_id"], blind_gift_data["original_gift_name"]
            event = BlindGiftEvent(
                source, sender, fans_medal, honor_level,
                blind_gift_id, gift_id, blind_gift_name, gift_name,
                count, blind_gift_price, price, profit
            )
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.BlindGiftEvent, room.room_id)

        @executor.on("SUPER_CHAT_MESSAGE", room.room_id, channel="RAW")
        async def on_super_chat(raw):
            """
            原始醒目留言事件

            发布事件:
                - :class:`SuperChatEvent`: 醒目留言事件
            """
            base = raw["data"]["data"]
            user_info = base["user_info"]
            super_chat_uid, super_chat_uname, super_chat_face = base["uid"], user_info["uname"], user_info["face"]
            sender = UserInfo(super_chat_face, super_chat_uid, super_chat_uname)

            fans_medal = None
            medal_info = base["medal_info"]
            if medal_info["target_id"]:
                (
                    fans_medal_level, fans_medal_name,
                    fans_medal_uid, fans_medal_uname, fans_medal_room_id, fans_medal_face
                ) = (
                    medal_info["medal_level"], medal_info["medal_name"],
                    medal_info["target_id"], "", 0, ""
                )

                if self.auto_complete:
                    fans_medal_uname, fans_medal_room_id, fans_medal_face = await LiveRoom.query_user_info(
                        fans_medal_uid
                    )

                fans_medal = FansMedalInfo(
                    fans_medal_face, fans_medal_uid, fans_medal_uname, fans_medal_room_id,
                    fans_medal_name, fans_medal_level
                )

            content, price = base["message"], base["price"]

            event = SuperChatEvent(source, sender, fans_medal, content, price)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.SuperChatEvent, room.room_id)

        @executor.on("GUARD_BUY", room.room_id, channel="RAW")
        async def on_guard(raw):
            """
            原始开通大航海事件

            发布事件:
                - :class:`CaptainEvent`: 开通舰长事件
                - :class:`CommanderEvent`: 开通提督事件
                - :class:`GovernorEvent`: 开通总督事件
            """
            base = raw["data"]["data"]
            guard_uid, guard_uname = base["uid"], base["username"]
            sender = UserInfo("", guard_uid, guard_uname)
            if self.auto_complete:
                _, _, sender.face = await LiveRoom.query_user_info(guard_uid)

            guard_type, month = base["gift_name"], base["num"]

            if guard_type == "舰长":
                event = CaptainEvent(source, sender, month)
                event_type = LiveEvent.CaptainEvent
            elif guard_type == "提督":
                event = CommanderEvent(source, sender, month)
                event_type = LiveEvent.CommanderEvent
            elif guard_type == "总督":
                event = GovernorEvent(source, sender, month)
                event_type = LiveEvent.GovernorEvent
            else:
                logger.warning(f"未识别的开通大航海事件: {raw}")
                return

            executor.dispatch(event, EventType.LiveEvent, event_type, room.room_id)

        @executor.on("INTERACT_WORD", room.room_id, channel="RAW")
        async def on_enter_room(raw):
            """
            原始进房或关注事件

            发布事件:
                - :class:`EnterRoomEvent`: 进房事件
                - :class:`FollowEvent`: 关注事件
            """
            base = raw["data"]["data"]
            enter_uid, enter_uname = base["uid"], base["uname"]
            sender = UserInfo("", enter_uid, enter_uname)
            if self.auto_complete:
                _, _, sender.face = await LiveRoom.query_user_info(enter_uid)

            fans_medal = None
            medal_info = base["fans_medal"]
            if medal_info["target_id"]:
                (
                    fans_medal_level, fans_medal_name,
                    fans_medal_uid, fans_medal_uname, fans_medal_room_id, fans_medal_face
                ) = (
                    medal_info["medal_level"], medal_info["medal_name"],
                    medal_info["target_id"], "", medal_info["anchor_roomid"], ""
                )

                if self.auto_complete:
                    fans_medal_uname, _, fans_medal_face = await LiveRoom.query_user_info(fans_medal_uid)

                fans_medal = FansMedalInfo(
                    fans_medal_face, fans_medal_uid, fans_medal_uname, fans_medal_room_id,
                    fans_medal_name, fans_medal_level
                )

            msg_type = base["msg_type"]

            if msg_type == 1:
                event = EnterRoomEvent(source, sender, fans_medal)
                event_type = LiveEvent.EnterRoomEvent
            elif msg_type == 2:
                event = FollowEvent(source, sender, fans_medal)
                event_type = LiveEvent.FollowEvent
            else:
                logger.warning(f"未识别的提示信息: {raw}")
                return

            executor.dispatch(event, EventType.LiveEvent, event_type, room.room_id)

        @executor.on("WATCHED_CHANGE", room.room_id, channel="RAW")
        async def on_watched_update(raw):
            """
            原始观看过人数更新事件

            发布事件:
                - :class:`WatchedUpdateEvent`: 观看过人数更新事件
            """
            count = raw["data"]["data"]["num"]

            event = WatchedUpdateEvent(source, count)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.WatchedUpdateEvent, room.room_id)

        @executor.on("LIKE_INFO_V3_CLICK", room.room_id, channel="RAW")
        async def on_like_click(raw):
            """
            原始点赞事件

            发布事件:
                - :class:`LikeClickEvent`: 点赞事件
            """
            base = raw["data"]["data"]
            like_uid, like_uname = base["uid"], base["uname"]
            sender = UserInfo("", like_uid, like_uname)
            if self.auto_complete:
                _, _, sender.face = await LiveRoom.query_user_info(like_uid)

            fans_medal = None
            medal_info = base["fans_medal"]
            if medal_info["target_id"]:
                (
                    fans_medal_level, fans_medal_name,
                    fans_medal_uid, fans_medal_uname, fans_medal_room_id, fans_medal_face
                ) = (
                    medal_info["medal_level"], medal_info["medal_name"],
                    medal_info["target_id"], "", 0, ""
                )

                if self.auto_complete:
                    fans_medal_uname, fans_medal_room_id, fans_medal_face = await LiveRoom.query_user_info(
                        fans_medal_uid
                    )

                fans_medal = FansMedalInfo(
                    fans_medal_face, fans_medal_uid, fans_medal_uname, fans_medal_room_id,
                    fans_medal_name, fans_medal_level
                )

            icon = base["like_icon"]

            event = LikeClickEvent(source, sender, fans_medal, icon)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.LikeClickEvent, room.room_id)

        @executor.on("LIKE_INFO_V3_UPDATE", room.room_id, channel="RAW")
        async def on_like_update(raw):
            """
            原始点赞数更新事件

            发布事件:
                - :class:`LikeUpdateEvent`: 点赞数更新事件
            """
            count = raw["data"]["data"]["click_count"]

            event = LikeUpdateEvent(source, count)
            executor.dispatch(event, EventType.LiveEvent, LiveEvent.LikeUpdateEvent, room.room_id)

        return room.uid

    async def remove(self, uid: int) -> NoReturn:
        """
        移除监听直播间

        Args:
            uid: 主播 UID
        """
        if uid not in self.__rooms:
            raise LiveListenerException(f"未添加过 UID: {uid} 的直播间监听")

        room = self.__rooms.pop(uid)
        if room.connect in executor:
            executor.remove_queue_task(room.connect)
        else:
            await room.disconnect()

        executor.remove_event_listener("CONNECTED", room.room_id, channel="RAW")
        executor.remove_event_listener("TIMEOUT", room.room_id, channel="RAW")
        executor.remove_event_listener("LIVE", room.room_id, channel="RAW")
        executor.remove_event_listener("PREPARING", room.room_id, channel="RAW")
        executor.remove_event_listener("DANMU_MSG", room.room_id, channel="RAW")
        executor.remove_event_listener("SEND_GIFT", room.room_id, channel="RAW")
        executor.remove_event_listener("SUPER_CHAT_MESSAGE", room.room_id, channel="RAW")
        executor.remove_event_listener("GUARD_BUY", room.room_id, channel="RAW")
        executor.remove_event_listener("INTERACT_WORD", room.room_id, channel="RAW")
        executor.remove_event_listener("WATCHED_CHANGE", room.room_id, channel="RAW")
        executor.remove_event_listener("LIKE_INFO_V3_CLICK", room.room_id, channel="RAW")
        executor.remove_event_listener("LIKE_INFO_V3_UPDATE", room.room_id, channel="RAW")


listener: LiveEventListener = LiveEventListener()
