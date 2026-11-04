import asyncio
import os
import tempfile
import time
from collections import defaultdict
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from shazamio import Shazam
import acoustid

MAX_AUDIO_BYTES = 2 * 1024 * 1024
MAX_SAMPLE_SECONDS = 12
MIN_SAMPLE_BYTES = 8_000
RATE_LIMIT_SECONDS = 8.0

last_request: dict[str, float] = defaultdict(float)
recognition_lock = asyncio.Semaphore(2)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.shazam = Shazam(segment_duration_seconds=12)
    yield
    await app.state.shazam.close()


app = FastAPI(title="SpotTV Free Recognition Bridge", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "provider": "ShazamIO"}


def _client_ip(request: Request) -> str:
    # On Render, trust its forwarded client IP. Otherwise use the direct peer.
    forwarded = request.headers.get("x-forwarded-for", "").split(",")[-1].strip()
    return forwarded or (request.client.host if request.client else "unknown")


def _track_payload(result: dict[str, Any]) -> dict[str, Any] | None:
    track = result.get("track") or {}
    title = str(track.get("title") or "").strip()
    artist = str(track.get("subtitle") or "").strip()
    if not title or not artist:
        return None

    images = track.get("images") or {}
    return {
        "provider": "shazamio",
        "title": title,
        "artist": artist,
        "album": "",
        "durationMs": 0,
        "providerTrackId": str(track.get("key") or ""),
        "artworkUrl": str(images.get("coverart") or ""),
        "trackUrl": str(track.get("url") or ""),
    }


@app.post("/v1/recognize")
async def recognize(request: Request, audio: UploadFile = File(...)) -> dict[str, Any]:
    ip = _client_ip(request)
    now = time.monotonic()
    if now - last_request[ip + ":shazam"] < RATE_LIMIT_SECONDS:
        raise HTTPException(status_code=429, detail="Please wait before trying another sample.")
    last_request[ip + ":shazam"] = now

    content_type = (audio.content_type or "").lower()
    if content_type not in {"audio/wav", "audio/x-wav", "audio/mpeg", "audio/ogg", "application/octet-stream"}:
        raise HTTPException(status_code=415, detail="Unsupported audio format.")

    data = await audio.read(MAX_AUDIO_BYTES + 1)
    if len(data) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Audio sample is too large.")
    if len(data) < MIN_SAMPLE_BYTES:
        raise HTTPException(status_code=400, detail="Audio sample is too short or empty.")

    # Shazam's recognition signature is designed around roughly 12 seconds.
    # Keep this endpoint deliberately small: samples are not stored on disk.
    async with recognition_lock:
        try:
            result = await request.app.state.shazam.recognize(data, max_skew=1e-3)
        except Exception as exc:
            raise HTTPException(status_code=502, detail="Recognition provider temporarily unavailable.") from exc

    track = _track_payload(result)
    if track is None:
        return {"matched": False, "provider": "shazamio"}
    return {"matched": True, "track": track}


@app.post("/v1/acoustid/recognize")
async def recognize_acoustid(request: Request, audio: UploadFile = File(...)) -> dict[str, Any]:
    client_key = os.getenv("ACOUSTID_CLIENT_KEY", "").strip()
    if not client_key:
        raise HTTPException(status_code=503, detail="AcoustID is not configured on this bridge.")
    ip = _client_ip(request)
    now = time.monotonic()
    if now - last_request[ip + ":acoustid"] < RATE_LIMIT_SECONDS:
        raise HTTPException(status_code=429, detail="Please wait before trying another sample.")
    last_request[ip + ":acoustid"] = now

    data = await audio.read(MAX_AUDIO_BYTES + 1)
    if len(data) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Audio sample is too large.")
    if len(data) < MIN_SAMPLE_BYTES:
        raise HTTPException(status_code=400, detail="Audio sample is too short or empty.")

    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp:
            temp.write(data)
            temp_path = temp.name
        duration, fingerprint = await asyncio.to_thread(acoustid.fingerprint_file, temp_path)
        result = await asyncio.to_thread(
            acoustid.lookup,
            client_key,
            fingerprint,
            duration,
            meta="recordings+releases+tracks+compress",
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail="AcoustID lookup temporarily unavailable.") from exc
    finally:
        if temp_path:
            try:
                os.unlink(temp_path)
            except OSError:
                pass

    if result.get("status") != "ok":
        raise HTTPException(status_code=502, detail="AcoustID returned an unsuccessful response.")

    best = None
    best_score = -1.0
    for candidate in result.get("results", []):
        score = float(candidate.get("score", 0))
        for recording in candidate.get("recordings", []):
            title = str(recording.get("title") or "").strip()
            artists = recording.get("artists") or []
            artist = "".join(
                str(item.get("name") or "") + str(item.get("joinphrase") or "")
                for item in artists
            ).strip()
            releases = recording.get("releases") or []
            album = str(releases[0].get("title") or "").strip() if releases else ""
            if title and artist and score > best_score:
                best_score = score
                best = {
                    "provider": "acoustid",
                    "title": title,
                    "artist": artist,
                    "album": album,
                    "durationMs": 0,
                    "providerTrackId": str(recording.get("id") or ""),
                    "artworkUrl": "",
                    "trackUrl": "",
                }
    # Short/noisy TV samples can produce weak accidental matches. Prefer no result to a
    # confidently wrong song; ShazamIO has already been tried before this fallback.
    if best is None or best_score < 0.60:
        return {"matched": False, "provider": "acoustid"}
    best["confidence"] = best_score
    return {"matched": True, "track": best}


@app.get("/")
async def root() -> dict[str, str]:
    return {"name": "SpotTV Free Recognition Bridge", "health": "/health"}
