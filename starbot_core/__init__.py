import asyncio
import platform
import sys

from loguru import logger

from .core.event import *
from .core.live import listener

if 'windows' in platform.system().lower():
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

logger_format = (
    "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
    "<level>{level: <8}</level> | "
    "<cyan>{name}</cyan>:<cyan>{line}</cyan> | "
    "<level>{message}</level>"
)
logger.remove()
logger.add(sys.stderr, format=logger_format, level="INFO")
