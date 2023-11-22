from .ApiException import ApiException


class LiveListenerException(ApiException):
    """
    直播间事件监听器异常
    """

    def __init__(self, msg: str):
        super().__init__()
        self.msg = msg
