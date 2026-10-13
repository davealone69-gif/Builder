"""Tests for CORS middleware and the production /docs flag."""

from __future__ import annotations

import importlib

import pytest
from fastapi.testclient import TestClient


def _fresh_app(monkeypatch, **env) -> TestClient:
    """Reload the backend module under the given env so its config is applied."""
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    import threedouble_backend.main as main

    importlib.reload(main)
    return TestClient(main.app)


@pytest.fixture()
def no_docs_prod(monkeypatch):
    return _fresh_app(
        monkeypatch,
        DD_ENVIRONMENT="production",
        DD_DOCS_ENABLED="false",
        DD_CORS_ORIGINS="http://localhost:3000",
    )


@pytest.fixture()
def docs_prod(monkeypatch):
    return _fresh_app(
        monkeypatch,
        DD_ENVIRONMENT="production",
        DD_DOCS_ENABLED="true",
    )


@pytest.fixture()
def dev(monkeypatch):
    return _fresh_app(monkeypatch, DD_ENVIRONMENT="development")


def test_cors_allowed_origin(no_docs_prod: TestClient) -> None:
    resp = no_docs_prod.options(
        "/presets",
        headers={
            "Origin": "http://localhost:3000",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert resp.status_code == 200
    assert resp.headers.get("access-control-allow-origin") == "http://localhost:3000"
    assert "GET" in resp.headers.get("access-control-allow-methods", "")


def test_cors_disallowed_origin(no_docs_prod: TestClient) -> None:
    resp = no_docs_prod.options(
        "/presets",
        headers={
            "Origin": "http://evil.example",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert resp.headers.get("access-control-allow-origin") is None


def test_cors_off_when_no_origins(dev: TestClient) -> None:
    resp = dev.options(
        "/presets",
        headers={
            "Origin": "http://localhost:3000",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert resp.headers.get("access-control-allow-origin") is None


def test_docs_hidden_in_production_by_default(no_docs_prod: TestClient) -> None:
    assert no_docs_prod.get("/docs").status_code == 404
    assert no_docs_prod.get("/redoc").status_code == 404
    assert no_docs_prod.get("/openapi.json").status_code == 404


def test_docs_shown_in_production_when_enabled(docs_prod: TestClient) -> None:
    assert docs_prod.get("/docs").status_code == 200
    assert docs_prod.get("/openapi.json").status_code == 200


def test_docs_shown_in_development(dev: TestClient) -> None:
    assert dev.get("/docs").status_code == 200
