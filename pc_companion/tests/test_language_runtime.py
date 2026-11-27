import tempfile
import unittest
import wave
from pathlib import Path

from pc_companion.language_runtime import (
    IndicConformerRuntime, LanguageRuntimeError, _read_pcm16_mono,
    require_reference_voice_consent,
)


class LanguageRuntimeTests(unittest.TestCase):
    def test_pawari_is_not_silently_mapped_to_hindi_or_marathi(self):
        with self.assertRaises(LanguageRuntimeError):
            IndicConformerRuntime("pav")

    def test_unknown_asr_language_fails_before_loading_models(self):
        with self.assertRaises(LanguageRuntimeError):
            IndicConformerRuntime("xx")

    def test_voice_synthesis_requires_explicit_owner_consent(self):
        with self.assertRaises(LanguageRuntimeError):
            require_reference_voice_consent(False)
        self.assertIsNone(require_reference_voice_consent(True))

    def test_pcm16_mono_wav_is_read_at_16khz(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.wav"
            with wave.open(str(path), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(16000)
                output.writeframes(b"\x00\x00" * 320)
            samples, rate = _read_pcm16_mono(path)
            self.assertEqual(rate, 16000)
            self.assertEqual(len(samples), 320)
            self.assertTrue(all(sample == 0.0 for sample in samples))

    def test_wrong_sample_rate_fails_with_actionable_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.wav"
            with wave.open(str(path), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(8000)
                output.writeframes(b"\x00\x00" * 160)
            with self.assertRaisesRegex(LanguageRuntimeError, "16 kHz"):
                _read_pcm16_mono(path)


if __name__ == "__main__":
    unittest.main()
