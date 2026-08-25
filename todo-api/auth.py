import os
import secrets

from fastapi import Header, HTTPException, status
from dotenv import load_dotenv

load_dotenv()

API_KEY = os.getenv("API_KEY", "")


def verify_api_key(x_api_key: str = Header(default="", alias="X-API-KEY")):
    if not API_KEY:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Server API_KEY is not configured",
        )
    # Constant-time comparison so response timing can't leak the key byte-by-byte.
    if not secrets.compare_digest(x_api_key.encode(), API_KEY.encode()):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid API key",
        )
    return True
