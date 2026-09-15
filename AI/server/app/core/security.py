from __future__ import annotations

import secrets

from fastapi import Header, HTTPException, status

from .config import Settings


def require_internal_token(settings: Settings, token: str | None = Header(default=None, alias="X-Internal-Token")) -> None:
    if not settings.internal_token:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail={"code": "SERVER_NOT_CONFIGURED"})
    if token is None or not secrets.compare_digest(token, settings.internal_token):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail={"code": "UNAUTHORIZED"})

