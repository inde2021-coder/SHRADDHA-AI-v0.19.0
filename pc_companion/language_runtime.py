"""Optional, local-only language runtimes for Shraddha's Windows companion.

Model files are user-installed, never auto-downloaded, and are loaded from local paths.
This module deliberately fails closed for unsupported language/model combinations.
"""
from __future__ import annotations

import os
import wave
from pathlib import Path
from typing import Optional

SUPPORTED_TRANSLATION = {"hi": "hin_Deva", "en": "eng_Latn", "mr": "mar_Deva"}
SUPPORTED_ASR = {"hi", "mr", "sa", "en"}
SUPPORTED_INDICF5_TTS = {"hi", "mr"}


class LanguageRuntimeError(RuntimeError):
    pass


def _required_file(env_name: str) -> Path:
    raw = os.environ.get(env_name, "").strip()
    if not raw:
        raise LanguageRuntimeError(f"{env_name} is not configured. Install the licensed model locally first.")
    path = Path(raw).expanduser().resolve()
    if not path.is_file():
        raise LanguageRuntimeError(f"Configured model file does not exist: {path}")
    return path


def _read_pcm16_mono(path: Path) -> tuple[list[float], int]:
    import array
    with wave.open(str(path), "rb") as wav:
        channels, width, rate = wav.getnchannels(), wav.getsampwidth(), wav.getframerate()
        frame_count = wav.getnframes()
        if width != 2 or channels not in (1, 2):
            raise LanguageRuntimeError("ASR input must be 16-bit PCM WAV with one or two channels.")
        if frame_count <= 0 or frame_count > rate * 600:
            raise LanguageRuntimeError("ASR audio must be between 0 and 10 minutes.")
        frames = wav.readframes(frame_count)
    samples = array.array("h")
    samples.frombytes(frames)
    if __import__("sys").byteorder != "little":
        samples.byteswap()
    if channels == 2:
        mono = [(samples[i] + samples[i + 1]) / 65536.0 for i in range(0, len(samples), 2)]
    else:
        mono = [sample / 32768.0 for sample in samples]
    if rate != 16000:
        raise LanguageRuntimeError(f"ASR input must be 16 kHz mono after downmix/resampling; file is {rate} Hz.")
    return mono, rate


class IndicConformerRuntime:
    """Sherpa-ONNX CTC adapter for local IndicConformer exports and English FastConformer."""

    def __init__(self, language: str):
        self.language = language.lower()
        if self.language not in SUPPORTED_ASR:
            raise LanguageRuntimeError(f"No verified ASR model is configured for language '{language}'.")
        self.model = _required_file("SHRADDHA_ASR_MODEL_" + self.language.upper())
        self.tokens = _required_file(
            "SHRADDHA_ASR_TOKENS_EN" if self.language == "en" else "SHRADDHA_ASR_TOKENS_INDIC"
        )
        try:
            import sherpa_onnx
        except ImportError as exc:
            raise LanguageRuntimeError("sherpa-onnx is not installed. Install pc_companion/requirements-language.txt.") from exc
        self._recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=str(self.model),
            tokens=str(self.tokens),
            num_threads=max(1, min(4, os.cpu_count() or 2)),
            decoding_method="greedy_search",
        )

    def transcribe_wav(self, wav_path: str | Path) -> str:
        try:
            import numpy as np
        except ImportError as exc:
            raise LanguageRuntimeError("NumPy is required by the Sherpa-ONNX runtime.") from exc
        audio, sample_rate = _read_pcm16_mono(Path(wav_path))
        stream = self._recognizer.create_stream()
        stream.accept_waveform(sample_rate, np.asarray(audio, dtype="float32"))
        self._recognizer.decode_stream(stream)
        result = stream.result.text.strip()
        if not result:
            raise LanguageRuntimeError("ASR returned an empty transcript. Try a clearer recording.")
        return result


class IndicTrans2Runtime:
    """Local-only IndicTrans2 Transformers adapter; no cloud fallback or model download."""

    def __init__(self, model_dir: str | Path):
        self.model_dir = Path(model_dir).expanduser().resolve()
        if not self.model_dir.is_dir() or not any(self.model_dir.iterdir()):
            raise LanguageRuntimeError("IndicTrans2 local model directory is missing or empty.")
        try:
            import torch
            from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
            from IndicTransToolkit import IndicProcessor
        except ImportError as exc:
            raise LanguageRuntimeError(
                "Install transformers, sentencepiece, torch and IndicTransToolkit from requirements-language.txt."
            ) from exc
        try:
            self.device = "cuda" if torch.cuda.is_available() else "cpu"
            # Never execute Python code shipped inside a downloaded model repository.
            self.tokenizer = AutoTokenizer.from_pretrained(str(self.model_dir), local_files_only=True)
            self.model = AutoModelForSeq2SeqLM.from_pretrained(
                str(self.model_dir), local_files_only=True, use_safetensors=True
            ).to(self.device)
            self.processor = IndicProcessor(inference=True)
        except Exception as exc:
            raise LanguageRuntimeError(f"Could not load the local IndicTrans2 checkpoint: {exc}") from exc

    def translate(self, text: str, source: str, target: str) -> str:
        source_code, target_code = SUPPORTED_TRANSLATION.get(source), SUPPORTED_TRANSLATION.get(target)
        if not source_code or not target_code:
            raise LanguageRuntimeError("IndicTrans2 adapter supports Hindi, English and Marathi only; Sanskrit and Pawari remain disabled until validated.")
        if not isinstance(text, str) or not text.strip() or len(text) > 5000:
            raise LanguageRuntimeError("Translation text must contain 1–5000 characters.")
        if source == target:
            return text.strip()
        try:
            import torch
            batch = self.processor.preprocess_batch([text.strip()], src_lang=source_code, tgt_lang=target_code)
            inputs = self.tokenizer(batch, truncation=True, padding="longest", return_tensors="pt").to(self.device)
            with torch.inference_mode():
                generated = self.model.generate(**inputs, num_beams=5, num_return_sequences=1, max_length=512)
            decoded = self.tokenizer.batch_decode(generated, skip_special_tokens=True, clean_up_tokenization_spaces=True)
            output = self.processor.postprocess_batch(decoded, lang=target_code)
            result = output[0].strip() if output else ""
            if not result:
                raise LanguageRuntimeError("IndicTrans2 returned an empty translation.")
            return result
        except LanguageRuntimeError:
            raise
        except Exception as exc:
            raise LanguageRuntimeError(f"IndicTrans2 translation failed: {exc}") from exc


class IndicF5Runtime:
    """Local IndicF5 adapter; requires a consented reference voice clip and its transcript."""

    def __init__(self, model_id: str = "ai4bharat/IndicF5"):
        try:
            import torch
            from transformers import AutoModel
        except ImportError as exc:
            raise LanguageRuntimeError("Install the optional IndicF5 dependencies first.") from exc
        try:
            self.device = "cuda" if torch.cuda.is_available() else "cpu"
            # Remote model code is not trusted or executed by the desktop companion.
            self.model = AutoModel.from_pretrained(
                model_id, local_files_only=True, use_safetensors=True
            ).to(self.device)
        except Exception as exc:
            raise LanguageRuntimeError(
                f"IndicF5 is not installed in the local model cache. Pre-stage licensed weights first: {exc}"
            ) from exc

    def synthesize(self, text: str, language: str, reference_audio: str | Path,
                   reference_transcript: str, output_wav: str | Path,
                   explicit_reference_voice_consent: bool = False) -> Path:
        require_reference_voice_consent(explicit_reference_voice_consent)
        language = language.lower()
        if language not in SUPPORTED_INDICF5_TTS:
            raise LanguageRuntimeError("IndicF5 in this integration is validated only for Hindi and Marathi; it does not provide English, Sanskrit or Pawari TTS.")
        if not isinstance(text, str) or not text.strip() or len(text) > 3000:
            raise LanguageRuntimeError("TTS text must contain 1–3000 characters.")
        reference = Path(reference_audio).expanduser().resolve()
        if (not reference.is_file() or not reference_transcript.strip()
                or len(reference_transcript.strip()) > 1000):
            raise LanguageRuntimeError("A local reference voice clip and its exact transcript (1–1000 characters) are required.")
        try:
            import numpy as np
            import soundfile as sf
            audio = np.asarray(self.model(
                text.strip(), ref_audio_path=str(reference), ref_text=reference_transcript.strip()
            ))
            if audio.dtype == np.int16:
                audio = audio.astype(np.float32) / 32768.0
            else:
                audio = audio.astype(np.float32)
            if audio.size == 0 or not np.isfinite(audio).all():
                raise LanguageRuntimeError("IndicF5 returned empty or non-finite audio.")
            target = Path(output_wav).expanduser().resolve()
            target.parent.mkdir(parents=True, exist_ok=True)
            sf.write(str(target), audio, samplerate=24000)
            if not target.is_file() or target.stat().st_size <= 44:
                raise LanguageRuntimeError("IndicF5 produced an empty audio file.")
            return target
        except LanguageRuntimeError:
            raise
        except Exception as exc:
            raise LanguageRuntimeError(f"IndicF5 synthesis failed: {exc}") from exc


def require_reference_voice_consent(consented: bool) -> None:
    if consented is not True:
        raise LanguageRuntimeError("Explicit permission from the reference voice owner is required before voice synthesis.")
