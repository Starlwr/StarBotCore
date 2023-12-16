import sys

from loguru import logger

from .core.event import *
from .core.live import listener

logger_format = (
    "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
    "<level>{level: <8}</level> | "
    "<cyan>{name}</cyan>:<cyan>{line}</cyan> | "
    "<level>{message}</level>"
)
logger.remove()
logger.add(sys.stderr, format=logger_format, level="INFO")
