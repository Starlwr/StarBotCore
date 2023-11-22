from loguru import logger
from starbot_executor import executor

from starbot_core import listener, EventType, LiveEvent, ConnectedEvent, DisconnectedEvent, DanmuEvent, BlindGiftEvent
from starbot_core.exception.LoginException import LoginException
from starbot_core.utils.network import credential


async def main():
    # 登录 B 站账号，不登录账号无法抓取完整数据，Cookie 获取方式：https://bot.starlwr.com/depoly/document
    try:
        await credential.login(
            sessdata="在此填入 SESSDATA 字段",
            bili_jct="在此填入 bili_jct 字段",
            buvid3="在此填入 buvid3 字段"
        )
    except LoginException as e:
        logger.error(e.msg, e)
        return

    # 例 1: 监听所有直播间的所有事件
    @executor.on()
    async def on_all(subjects, event):
        logger.info(f"{subjects}: {event}")

    # 例 2: 监听所有直播间的连接成功事件
    @executor.on(EventType.LiveEvent, LiveEvent.ConnectedEvent)
    async def on_connected(event: ConnectedEvent):
        logger.success(f"已成功连接到 {event.source.uname} 的直播间 {event.source.room_id}")

    # 例 3: 监听所有直播间的断开连接事件
    @executor.on(EventType.LiveEvent, LiveEvent.DisconnectedEvent)
    async def on_disconnected(event: DisconnectedEvent):
        logger.opt(colors=True).info(f"已断开连接 <cyan>{event.source.uname}</> 的直播间 <cyan>{event.source.room_id}</>")

    # 例 4: 监听 22889484 直播间的弹幕事件
    @executor.on(EventType.LiveEvent, LiveEvent.DanmuEvent, 22889484)
    async def on_danmu(event: DanmuEvent):
        if event.fans_medal is None:
            logger.info(f"{event.sender.uname} (无粉丝勋章): {event.content}")
        else:
            logger.info(
                f"{event.sender.uname} (Lv {event.fans_medal.level} - {event.fans_medal.name}): {event.content}")

    # 例 5: 监听所有直播间的盲盒礼物事件
    @executor.on(EventType.LiveEvent, LiveEvent.BlindGiftEvent)
    async def on_blind_gift(event: BlindGiftEvent):
        if event.profit >= 0:
            logger.info(f"{event.sender.uname} 通过 {event.blind_name} 开出了 {event.count} 个 {event.gift_name}, 赚了 {abs(event.profit)} 元")
        else:
            logger.info(f"{event.sender.uname} 通过 {event.blind_name} 开出了 {event.count} 个 {event.gift_name}, 亏了 {abs(event.profit)} 元")

    # 循环添加监听直播间
    room_ids = (22889484, 55634, 21013446)
    for room_id in room_ids:
        await listener.add(room_id=room_id)


listener.auto_complete = True  # 设置为 True 可以获得更完整的信息（部分事件中的头像、昵称等），但会因网络请求导致一定的延迟，请酌情使用
loop = executor.init()
loop.run_until_complete(main())
loop.run_forever()
