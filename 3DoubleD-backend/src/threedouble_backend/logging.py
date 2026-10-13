"""Structured (JSON) logging configuration for the 3Double D backend.

Emits one JSON object per log line via a custom formatter so logs are
machine-parseable (suitable for CloudWatch, Loki, DataDog, etc.).
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone
from typing import Any

_RESERVED = {
    "name",
    "msg",
    "args",
    "levelname",
    "levelno",
    "pathname",
    "filename",
    "module",
    "exc_info",
    "exc_text",
    "stack_info",
    "lineno",
    "funcName",
    "created",
    "msecs",
    "relativeCreated",
    "thread",
    "threadName",
    "processName",
    "process",
    "taskName",
}


class JsonFormatter(logging.Formatter):
    """Format log records as single-line JSON objects."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        # Include caller location when available.
        if record.pathname:
            payload["file"] = record.pathname
            payload["line"] = record.lineno

        # Merge any structured ``extra`` kwargs (skip logging-reserved keys).
        extra = getattr(record, "_structured", None)
        if isinstance(extra, dict):
            for key, value in extra.items():
                if key not in _RESERVED and key not in payload:
                    payload[key] = value

        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)

        return json.dumps(payload, default=str)


def get_logger(name: str = "threedouble") -> logging.Logger:
    """Return a JSON-structured logger configured for the given name."""
    logger = logging.getLogger(name)
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(JsonFormatter())
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        logger.propagate = False
    return logger
