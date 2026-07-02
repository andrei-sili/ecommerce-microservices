"""Tests for the Prometheus /metrics endpoint (Wave 5c observability)."""

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest.mark.asyncio
async def test_metrics_endpoint_returns_prometheus_text() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # Drive one instrumented request so the metric families carry samples.
        await client.get("/health")
        response = await client.get("/metrics")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    body = response.text
    assert "http_request_duration_seconds" in body
    assert "http_requests_total" in body


@pytest.mark.asyncio
async def test_metrics_not_exposed_in_openapi_schema() -> None:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/openapi.json")

    assert response.status_code == 200
    assert "/metrics" not in response.json()["paths"]
