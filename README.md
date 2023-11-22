<div align="center">

# StarBotCore

[![PyPI](https://img.shields.io/pypi/v/starbot-bilibili-core)](https://pypi.org/project/starbot-bilibili-core)
[![Python](https://img.shields.io/badge/python-3.10%20|%203.11-blue)](https://www.python.org)
[![License](https://img.shields.io/github/license/Starlwr/StarBotCore)](https://github.com/Starlwr/StarBotCore/blob/master/LICENSE)
[![STARS](https://img.shields.io/github/stars/Starlwr/StarBotCore?color=yellow&label=Stars)](https://github.com/Starlwr/StarBotCore/stargazers)

**一个优雅的异步 bilibili 直播间监听库**
</div>

## 特性

* 极简代码调用，可快速实现高级功能
* 细化原始事件，自动区分文字弹幕与表情弹幕，免费礼物、付费礼物与盲盒礼物等
* 基于分级消息主题的订阅发布模式，可灵活监听单个直播间或多个直播间
* 自动解析原始数据并包装为事件发布，无需手动使用代码处理原始数据

## 快速开始
### 安装

```shell
pip install starbot-bilibili-core
```

### 代码框架

在开始编写代码前，需要引入异步任务执行器 executor，并执行初始化操作

```python
from starbot_executor import executor

async def main():
    # 业务逻辑
    pass

loop = executor.init()
loop.run_until_complete(main())
loop.run_forever()
```

### 监听直播间

引入直播间事件监听器 listener 后，仅须一行代码即可添加监听直播间，并自动持续发布该直播间的相关事件

```python
await listener.add(room_id=22889484)
```

### 已支持的事件列表

- 连接成功事件（ConnectedEvent）
- 断开连接事件（DisconnectedEvent）
- 心跳响应超时事件（TimeoutEvent）
- 开播事件（LiveOnEvent）
- 下播事件（LiveOffEvent）
- 文字弹幕事件（DanmuEvent）
- 大表情弹幕事件（EmojiEvent）
- 免费礼物事件（SilverGiftEvent）
- 付费礼物事件（GoldGiftEvent）
- 盲盒礼物事件（BlindGiftEvent）
- 醒目留言事件（SuperChatEvent）
- 开通舰长事件（CaptainEvent）
- 开通提督事件（CommanderEvent）
- 开通总督事件（GovernorEvent）
- 进房事件（EnterRoomEvent）
- 关注事件（FollowEvent）
- 观看过人数更新事件（WatchedUpdateEvent）
- 点赞事件（LikeClickEvent）
- 点赞数更新事件（LikeUpdateEvent）

### 订阅-发布机制

消息主题为层级结构，较高层级的消息主题可同时监听到较低层级的事件，举例如下：  

弹幕事件对应消息主题为三层，一级主题为：EventType.LiveEvent，二级主题为：LiveEvent.DanmuEvent，三级主题为：房间号  
使用 **@executor.on(EventType.LiveEvent, LiveEvent.DanmuEvent, 房间号)** 装饰的方法，仅可监听到**相应房间号**的**弹幕**事件  
使用 **@executor.on(EventType.LiveEvent, LiveEvent.DanmuEvent)** 装饰的方法，可监听到**所有房间**的**弹幕**事件  
使用 **@executor.on(EventType.LiveEvent)** 或 **@executor.on()** 装饰的方法，可监听到**所有房间**的**所有**事件

### 完整示例

```python
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
```

## 鸣谢

* [StarBotExecutor](https://github.com/Starlwr/StarBotExecutor): 一个基于订阅发布模式的异步执行器
* [bilibili-api](https://github.com/MoyuScript/bilibili-api): 哔哩哔哩的 API 调用模块
* [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect): 哔哩哔哩 API 收集整理
