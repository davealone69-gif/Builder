"""FastAPI application for the 3Double D 3D avatar studio backend."""

from __future__ import annotations

import time
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from starlette.middleware.base import BaseHTTPMiddleware

from . import __version__
from .auth import require_token
from .config import Settings, get_settings
from .logging import get_logger
from .models import (
    Asset,
    Avatar,
    AvatarCreate,
    ExportCreate,
    ExportJob,
    HealthResponse,
    Preset,
    PresetCreate,
)
from .presets import ASSETS, PRESETS, get_assets, get_presets
from .storage import JsonStorage, new_id

settings = Settings.from_env()
storage = JsonStorage(settings.dd_data_file)
logger = get_logger()


class RequestLogMiddleware(BaseHTTPMiddleware):
    """Emit a structured JSON log line for every request."""

    async def dispatch(self, request: Request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        duration_ms = round((time.perf_counter() - start) * 1000, 2)
        logger.info(
            "http request",
            extra={
                "_structured": {
                    "method": request.method,
                    "path": request.url.path,
                    "status": response.status_code,
                    "duration_ms": duration_ms,
                    "client": request.client.host if request.client else None,
                }
            },
        )
        return response


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Log server lifecycle as structured JSON."""
    logger.info(
        "server started",
        extra={
            "_structured": {
                "version": __version__,
                "environment": settings.dd_environment,
                "docs_enabled": docs_url is not None,
                "cors_origins": settings.dd_cors_origins,
            }
        },
    )
    yield
    logger.info("server stopped")


# ``docs_url`` / ``redoc_url`` are hidden in production unless explicitly
# re-enabled via DD_DOCS_ENABLED=true. Development always exposes them.
docs_enabled = settings.dd_docs_enabled if settings.is_production else True
docs_url = "/docs" if docs_enabled else None
redoc_url = "/redoc" if docs_enabled else None

app = FastAPI(
    title="3Double D Backend",
    description="REST API for the 3Double D photorealistic 3D avatar studio.",
    version=__version__,
    docs_url=docs_url,
    redoc_url=redoc_url,
    openapi_url="/openapi.json" if docs_url else None,
    lifespan=lifespan,
)

# CORS — allow the Android app (or a web client) origins listed in config.
if settings.dd_cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.dd_cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

app.add_middleware(RequestLogMiddleware)

AuthDep = Annotated[None, Depends(require_token)]


@app.get("/health", response_model=HealthResponse, tags=["system"])
def health() -> HealthResponse:
    """Liveness check."""
    return HealthResponse(status="ok", version=__version__, environment=settings.dd_environment)


# ---- Presets -----------------------------------------------------------
@app.get("/presets", response_model=list[Preset], tags=["presets"])
def list_presets() -> list[Preset]:
    """List themed presets (Vampire, Medieval, Demon, Cyberpunk, ...)."""
    return get_presets()


@app.get("/presets/{preset_id}", response_model=Preset, tags=["presets"])
def get_preset(preset_id: str) -> Preset:
    """Return a single preset by id."""
    for preset in PRESETS:
        if preset.id == preset_id:
            return preset
    raise HTTPException(status_code=404, detail="Preset not found")


@app.post("/presets", response_model=Preset, status_code=201, tags=["presets"])
def create_preset(payload: PresetCreate) -> Preset:
    """Register a custom preset (in-memory for this session)."""
    preset = Preset(id=new_id(), **payload.model_dump())
    PRESETS.append(preset)
    return preset


# ---- Assets ------------------------------------------------------------
@app.get("/assets", response_model=list[Asset], tags=["assets"])
def list_assets() -> list[Asset]:
    """List GLB asset metadata available to the client."""
    return get_assets()


@app.get("/assets/{asset_id}", response_model=Asset, tags=["assets"])
def get_asset(asset_id: str) -> Asset:
    """Return a single asset by id."""
    for asset in ASSETS:
        if asset.id == asset_id:
            return asset
    raise HTTPException(status_code=404, detail="Asset not found")


# ---- Avatars (protected) ----------------------------------------------
@app.get("/avatars", response_model=list[Avatar], tags=["avatars"])
def list_avatars(_: AuthDep) -> list[Avatar]:
    """List saved avatars."""
    return [Avatar(**a) for a in storage.list_items("avatars")]


@app.post("/avatars", response_model=Avatar, status_code=201, tags=["avatars"])
def create_avatar(payload: AvatarCreate, _: AuthDep) -> Avatar:
    """Save a new avatar."""
    avatar = Avatar(id=new_id(), **payload.model_dump())
    storage.insert_item("avatars", avatar.model_dump())
    return avatar


@app.get("/avatars/{avatar_id}", response_model=Avatar, tags=["avatars"])
def get_avatar(avatar_id: str, _: AuthDep) -> Avatar:
    """Fetch a single avatar."""
    data = storage.get_item("avatars", avatar_id)
    if data is None:
        raise HTTPException(status_code=404, detail="Avatar not found")
    return Avatar(**data)


@app.put("/avatars/{avatar_id}", response_model=Avatar, tags=["avatars"])
def update_avatar(avatar_id: str, payload: AvatarCreate, _: AuthDep) -> Avatar:
    """Update an existing avatar."""
    updated = storage.update_item("avatars", avatar_id, payload.model_dump())
    if updated is None:
        raise HTTPException(status_code=404, detail="Avatar not found")
    return Avatar(**updated)


@app.delete("/avatars/{avatar_id}", status_code=204, tags=["avatars"])
def delete_avatar(avatar_id: str, _: AuthDep) -> Response:
    """Delete an avatar. Returns 204 No Content on success."""
    if not storage.delete_item("avatars", avatar_id):
        raise HTTPException(status_code=404, detail="Avatar not found")
    return Response(status_code=204)


# ---- Export jobs (protected) ------------------------------------------
@app.post("/exports", response_model=ExportJob, status_code=201, tags=["exports"])
def create_export(payload: ExportCreate, _: AuthDep) -> ExportJob:
    """Submit an export (render) job; returns an id to poll."""
    if storage.get_item("avatars", payload.avatar_id) is None:
        raise HTTPException(status_code=404, detail="Avatar not found")
    job = ExportJob(id=new_id(), avatar_id=payload.avatar_id, format=payload.format)
    storage.insert_item("exports", job.model_dump())
    return job


@app.get("/exports/{job_id}", response_model=ExportJob, tags=["exports"])
def get_export(job_id: str, _: AuthDep) -> ExportJob:
    """Poll the status of an export job."""
    data = storage.get_item("exports", job_id)
    if data is None:
        raise HTTPException(status_code=404, detail="Export job not found")
    return ExportJob(**data)
