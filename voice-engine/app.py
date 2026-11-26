import os
import subprocess
import tempfile
import wave
from math import gcd
from pathlib import Path
import hmac
import threading

import numpy as np
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field
from scipy.signal import resample_poly

app = FastAPI(title="Shraddha Custom Voice Engine", docs_url=None, redoc_url=None)
_SYNTHESIS_LOCK = threading.BoundedSemaphore(value=1)

class SpeechRequest(BaseModel):
    text: str = Field(min_length=1, max_length=6000)
    language: str = Field(default="hi", pattern="^(hi|en)$")
    voice: str = Field(default="shraddha", max_length=80)
    format: str = "pcm_s16le"
    sample_rate: int = 24000
    channels: int = 1

def require_auth(authorization: str | None):
    expected = os.getenv("SHRADDHA_TTS_TOKEN", "")
    supplied = (authorization or "").removeprefix("Bearer ").strip()
    if not expected or not supplied or not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="Voice engine authentication failed")

@app.get("/health")
def health():
    return {"ok": True, "service": "Shraddha custom voice engine", "modelsConfigured": {
        "hi": bool(os.getenv("SHRADDHA_MODEL_HI")),
        "en": bool(os.getenv("SHRADDHA_MODEL_EN")),
    }}

@app.post("/synthesize")
def synthesize(body: SpeechRequest, authorization: str | None = Header(default=None)):
    require_auth(authorization)
    if body.format != "pcm_s16le" or body.sample_rate != 24000 or body.channels != 1:
        raise HTTPException(status_code=400, detail="Only mono 24 kHz PCM s16le is supported")
    model = os.getenv("SHRADDHA_MODEL_HI" if body.language == "hi" else "SHRADDHA_MODEL_EN", "")
    model_path = Path(model)
    if not model or not model_path.is_file() or not Path(str(model_path) + ".json").is_file():
        raise HTTPException(status_code=503, detail=f"Licensed Shraddha {body.language} voice model is not installed")
    if not _SYNTHESIS_LOCK.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="Voice engine is busy; retry shortly")
    try:
        return render_pcm(body, model_path)
    finally:
        _SYNTHESIS_LOCK.release()


def render_pcm(body: SpeechRequest, model_path: Path) -> Response:
    with tempfile.TemporaryDirectory(prefix="shraddha-tts-") as temp:
        wav_path = Path(temp) / "speech.wav"
        try:
            subprocess.run(
                ["piper", "--model", str(model_path), "--output_file", str(wav_path)],
                input=body.text.strip().encode("utf-8"),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                timeout=45,
                check=True,
            )
        except subprocess.TimeoutExpired:
            raise HTTPException(status_code=504, detail="Voice synthesis timed out")
        except subprocess.CalledProcessError:
            raise HTTPException(status_code=502, detail="Voice model synthesis failed")
        try:
            with wave.open(str(wav_path), "rb") as wav:
                channels, rate, width = wav.getnchannels(), wav.getframerate(), wav.getsampwidth()
                frames = wav.readframes(wav.getnframes())
            if width != 2 or channels < 1 or rate < 8000 or rate > 96000:
                raise ValueError("Unsupported model audio format")
            audio = np.frombuffer(frames, dtype="<i2")
            if channels > 1:
                audio = audio.reshape(-1, channels).mean(axis=1)
            if rate != 24000:
                factor = gcd(24000, rate)
                audio = resample_poly(audio.astype(np.float32), 24000 // factor, rate // factor)
            pcm = np.clip(np.rint(audio), -32768, 32767).astype("<i2").tobytes()
            if len(pcm) < 2 or len(pcm) > 3_000_000:
                raise ValueError("Synthesized audio is outside the allowed size")
            return Response(content=pcm, media_type="audio/pcm", headers={
                "X-Audio-Sample-Rate": "24000", "X-Audio-Channels": "1",
                "X-Audio-Format": "s16le", "Cache-Control": "no-store"
            })
        except Exception:
            raise HTTPException(status_code=502, detail="Voice model returned invalid audio")
