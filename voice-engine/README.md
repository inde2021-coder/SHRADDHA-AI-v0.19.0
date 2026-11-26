# Shraddha Custom Voice Engine (non-Google)

This service runs Piper TTS models that you supply and returns raw mono PCM signed-16 little-endian audio at 24 kHz. It is separate from Gemini and Android system TTS.

## Voice identity
- Supply licensed Hindi and English models designed/trained for the same Shraddha voice identity.
- A stock Piper voice is only a pretrained voice, not a unique Shraddha identity.
- Do not clone a real person's voice without explicit informed permission and rights to use the recordings.
- No model weights are committed to this repository.

## Run with Docker
Place model files and matching `.onnx.json` files in a local `models/` directory. Configure `SHRADDHA_TTS_TOKEN`, `SHRADDHA_MODEL_HI=/models/shraddha-hi.onnx`, and `SHRADDHA_MODEL_EN=/models/shraddha-en.onnx`.

```bash
docker build -t shraddha-custom-voice .
docker run --rm -p 8000:8000 -v "$PWD/models:/models:ro" \
  -e SHRADDHA_TTS_TOKEN="replace-with-long-random-secret" \
  -e SHRADDHA_MODEL_HI=/models/shraddha-hi.onnx \
  -e SHRADDHA_MODEL_EN=/models/shraddha-en.onnx \
  shraddha-custom-voice
```

Expose only behind a trusted HTTPS reverse proxy. Set backend environment `SHRADDHA_TTS_URL=https://your-voice-host.example/synthesize`, `SHRADDHA_TTS_TOKEN` to the same secret, and `SHRADDHA_TTS_VOICE=shraddha`.

## API
POST `/synthesize` with Bearer token and JSON:

```json
{"text":"नमस्ते","language":"hi","voice":"shraddha","format":"pcm_s16le","sample_rate":24000,"channels":1}
```

Response: `audio/pcm`, 24 kHz mono signed-16 little-endian PCM. Health: `GET /health`.

Use a persistent CPU/GPU host or container service, not a short-lived serverless function. The engine accepts one synthesis job at a time and returns HTTP 429 while busy. Keep models read-only, protect the endpoint with TLS and a strong token, limit request size/rate, and do not log speech text. The relay requires `SHRADDHA_TTS_TOKEN` (16-512 characters) and sends it as a Bearer token; configure the same secret on both services.
