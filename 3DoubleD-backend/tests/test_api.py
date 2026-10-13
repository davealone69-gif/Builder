"""Integration tests for the 3Double D backend API."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from threedouble_backend.config import Settings
from threedouble_backend.main import app, storage, settings


@pytest.fixture(autouse=True)
def _fresh_storage(tmp_path, monkeypatch):
    """Point storage at a temp file and reset in-memory collections."""
    settings = Settings.from_env()
    settings.dd_data_file = str(tmp_path / "data.json")
    # Reset the module-level storage to the temp file for isolation.
    from threedouble_backend import main

    main.storage = type(storage)(str(tmp_path / "data.json"))
    return main.storage


@pytest.fixture()
def client() -> TestClient:
    return TestClient(app)


@pytest.fixture()
def token() -> str:
    return "changeme"


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_health(client: TestClient) -> None:
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"


def test_list_presets(client: TestClient) -> None:
    resp = client.get("/presets")
    assert resp.status_code == 200
    presets = resp.json()
    names = {p["name"] for p in presets}
    assert {"Vampire", "Medieval", "Demon", "Cyberpunk"} <= names


def test_get_preset(client: TestClient) -> None:
    resp = client.get("/presets/vampire")
    assert resp.status_code == 200
    assert resp.json()["primary_material"] == "Pale"


def test_get_preset_missing(client: TestClient) -> None:
    resp = client.get("/presets/nope")
    assert resp.status_code == 404


def test_list_assets(client: TestClient) -> None:
    resp = client.get("/assets")
    assert resp.status_code == 200
    assert len(resp.json()) == 3


def test_avatars_require_token(client: TestClient) -> None:
    resp = client.get("/avatars")
    assert resp.status_code == 401


def test_avatar_crud(client: TestClient, token: str) -> None:
    headers = auth_headers(token)
    create = client.post(
        "/avatars",
        json={"name": "Nyx", "preset_id": "cyberpunk", "morphs": {"body_build": 0.7}},
        headers=headers,
    )
    assert create.status_code == 201
    avatar = create.json()
    aid = avatar["id"]

    listed = client.get("/avatars", headers=headers)
    assert any(a["id"] == aid for a in listed.json())

    fetched = client.get(f"/avatars/{aid}", headers=headers)
    assert fetched.json()["name"] == "Nyx"

    updated = client.put(
        f"/avatars/{aid}",
        json={"name": "Nyx v2", "preset_id": "vampire", "morphs": {}},
        headers=headers,
    )
    assert updated.json()["name"] == "Nyx v2"

    deleted = client.delete(f"/avatars/{aid}", headers=headers)
    assert deleted.status_code == 204
    assert client.get(f"/avatars/{aid}", headers=headers).status_code == 404


def test_export_flow(client: TestClient, token: str) -> None:
    headers = auth_headers(token)
    avatar = client.post(
        "/avatars",
        json={"name": "Render target", "morphs": {}},
        headers=headers,
    ).json()

    created = client.post(
        "/exports",
        json={"avatar_id": avatar["id"], "format": "png"},
        headers=headers,
    )
    assert created.status_code == 201
    job = created.json()
    assert job["status"] == "queued"

    polled = client.get(f"/exports/{job['id']}", headers=headers)
    assert polled.json()["format"] == "png"


def test_export_missing_avatar(client: TestClient, token: str) -> None:
    resp = client.post(
        "/exports",
        json={"avatar_id": "does-not-exist", "format": "png"},
        headers=auth_headers(token),
    )
    assert resp.status_code == 404


def test_invalid_token(client: TestClient) -> None:
    resp = client.get("/avatars", headers={"Authorization": "Bearer wrong"})
    assert resp.status_code == 401
