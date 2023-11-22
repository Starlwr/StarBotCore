from .ApiException import ApiException


class LoginException(ApiException):
    """
    登录异常
    """

    def __init__(self, msg: str):
        super().__init__()
        self.msg = msg
