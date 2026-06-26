from fastapi import APIRouter
from fastapi.responses import JSONResponse

router = APIRouter()


@router.get("/health")
async def health() -> JSONResponse:
    """Liveness/readiness probe used by the Docker Compose healthcheck."""
    return JSONResponse({"status": "ok"})
