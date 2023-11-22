import asyncio
import atexit
import json
import re
from typing import Any, Union, Dict, Optional, NoReturn

import aiohttp
from aiohttp import TCPConnector, ServerDisconnectedError, ClientConnectorError

from ..exception.LoginException import LoginException
from ..exception.NetworkException import NetworkException
from ..exception.ResponseCodeException import ResponseCodeException
from ..exception.ResponseException import ResponseException

__session_pool = {}


class Credential:
    """
    登录凭据类
    """
    sessdata: Optional[str]
    """登录 B 站账号所需 Cookie 数据中 SESSDATA 字段，Cookie 获取方式：https://bot.starlwr.com/depoly/document"""

    bili_jct: Optional[str]
    """登录 B 站账号所需 Cookie 数据中 bili_jct 字段，Cookie 获取方式：https://bot.starlwr.com/depoly/document"""

    buvid3: Optional[str]
    """登录 B 站账号所需 Cookie 数据中 buvid3 字段，Cookie 获取方式：https://bot.starlwr.com/depoly/document"""

    login_uid: int
    """已登录账号 UID"""

    login_uname: str
    """已登录账号昵称"""

    def __init__(self):
        self.sessdata = None
        self.bili_jct = None
        self.buvid3 = None
        self.login_uid = 0
        self.login_uname = ""

    async def login(self, sessdata: str, bili_jct: str, buvid3: str) -> NoReturn:
        self.sessdata = sessdata
        self.bili_jct = bili_jct
        self.buvid3 = buvid3

        try:
            response = await request("GET", "https://api.bilibili.com/x/space/v2/myinfo")
            profile = response["profile"]
            uid = profile["mid"]
            uname = profile["name"]
            self.login_uid = uid
            self.login_uname = uname
        except ResponseCodeException as ex:
            if ex.code == -101:
                raise LoginException("尝试登录 B 站账号失败, 可能的原因为登录凭据填写不正确或已失效, 请检查后重试")
        except Exception as ex:
            raise LoginException(f"尝试登录 B 站账号失败: {ex}")

    def get_cookies(self) -> Dict[str, str]:
        return {"SESSDATA": self.sessdata, "buvid3": self.buvid3, 'bili_jct': self.bili_jct}


credential: Credential = Credential()


@atexit.register
def __clean():
    """
    程序退出清理操作
    """
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return

    async def __clean_task():
        await __session_pool[loop].close()

    if loop.is_closed():
        loop.run_until_complete(__clean_task())
    else:
        loop.create_task(__clean_task())


async def request(method: str,
                  url: str,
                  params: dict = None,
                  data: Any = None,
                  no_csrf: bool = False,
                  json_body: bool = False,
                  **kwargs) -> Union[Dict, None]:
    """
    向接口发送请求

    Args:
        method: 请求方法
        url: 请求 URL
        params: 请求参数。默认：None
        data: 请求载荷。默认：None
        no_csrf: 不要自动添加 CSRF。默认：False
        json_body: 载荷是否为 JSON。默认：False
        kwargs: 暂不使用

    Returns:
        接口未返回数据时，返回 None，否则返回该接口提供的 data 或 result 字段的数据
    """
    method = method.upper()

    # 使用 Referer 和 UA 请求头以绕过反爬虫机制
    default_headers = {
        "Referer": "https://www.bilibili.com",
        "User-Agent": "Mozilla/5.0"
    }
    headers = default_headers

    if params is None:
        params = {}

    # 自动添加 csrf
    if not no_csrf and method in ['POST', 'DELETE', 'PATCH']:
        if data is None:
            data = {}
        data['csrf'] = credential.bili_jct
        data['csrf_token'] = credential.bili_jct

    # jsonp
    if params.get("jsonp", "") == "jsonp":
        params["callback"] = "callback"

    args = {
        "method": method,
        "url": url,
        "params": params,
        "data": data,
        "headers": headers,
        "cookies": credential.get_cookies()
    }

    args.update(kwargs)

    if json_body:
        args["headers"]["Content-Type"] = "application/json"
        args["data"] = json.dumps(args["data"])

    session = get_session()

    for i in range(3):
        try:
            async with session.request(**args) as resp:
                # 检查状态码
                try:
                    resp.raise_for_status()
                except aiohttp.ClientResponseError as e:
                    raise NetworkException(e.status, e.message)

                # 检查响应头 Content-Length
                content_length = resp.headers.get("content-length")
                if content_length and int(content_length) == 0:
                    return None

                # 检查响应头 Content-Type
                content_type = resp.headers.get("content-type")

                # 不是 application/json
                if content_type.lower().index("application/json") == -1:
                    raise ResponseException("响应不是 application/json 类型")

                raw_data = await resp.text()
                resp_data: dict

                if 'callback' in params:
                    # JSONP 请求
                    resp_data = json.loads(
                        re.match("^.*?({.*}).*$", raw_data, re.S).group(1))
                else:
                    # JSON
                    resp_data = json.loads(raw_data)

                # 检查 code
                code = resp_data.get("code", None)

                if code is None:
                    raise ResponseCodeException(-1, "API 返回数据未含 code 字段", resp_data)

                if code != 0:
                    # 4101131: 加载错误，请稍后再试, 22015: 您的账号异常，请稍后再试
                    if code == 4101131 or code == 22015:
                        await asyncio.sleep(10)
                        continue

                    msg = resp_data.get('msg', None)
                    if msg is None:
                        msg = resp_data.get('message', None)
                    if msg is None:
                        msg = "接口未返回错误信息"
                    raise ResponseCodeException(code, msg, resp_data)

                real_data = resp_data.get("data", None)
                if real_data is None:
                    real_data = resp_data.get("result", None)
                return real_data
        except ClientConnectorError:
            await asyncio.sleep(3)
            continue
        except ServerDisconnectedError:
            await asyncio.sleep(3)
            continue
        except NetworkException:
            await asyncio.sleep(3)
            continue


def get_session() -> aiohttp.ClientSession:
    """
    获取当前模块的 aiohttp.ClientSession 对象，用于自定义请求

    Returns:
        ClientSession 实例
    """
    loop = asyncio.get_running_loop()
    session = __session_pool.get(loop, None)
    if session is None:
        session = aiohttp.ClientSession(
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.71 Safari/537.36 Core/1.94.201.400 QQBrowser/11.9.5325.400"
            }, loop=loop, connector=TCPConnector(loop=loop, limit=0, verify_ssl=False)
        )
        __session_pool[loop] = session

    return session


def set_session(session: aiohttp.ClientSession):
    """
    用户手动设置 Session

    Args:
        session: aiohttp.ClientSession 实例
    """
    loop = asyncio.get_running_loop()
    __session_pool[loop] = session
