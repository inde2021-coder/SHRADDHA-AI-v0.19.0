import csv
import tempfile
import unittest
import wave
from pathlib import Path

from tools.pawari_dataset_validator import validate_csv

HEADERS = [
    "record_id","profile_id","state","district","tehsil_or_block_optional","village_optional",
    "speaker_self_identified_variety","preferred_script","utterance_type","language_mix",
    "transcript_original_script","hindi_gloss","english_gloss","audio_filename","audio_format",
    "sample_rate_hz","bit_depth","recording_environment","consent_transcription","consent_research",
    "consent_public_release","consent_commercial_training","consent_voice_synthesis",
    "attribution_preference","reviewer_1_id","reviewer_1_status","reviewer_2_id",
    "reviewer_2_status","disagreement_notes","collection_date","status","withdrawal_contact_ref"
]

def sample(**overrides):
    row = {key: "x" for key in HEADERS}
    row.update({
        "record_id": "sample_0001", "profile_id": "pawari-balaghat-mp",
        "state": "Madhya Pradesh", "district": "Balaghat",
        "speaker_self_identified_variety": "Pawari (speaker's own label)",
        "preferred_script": "Devanagari", "utterance_type": "conversation",
        "transcript_original_script": "स्थानीय नमूना वाक्य",
        "audio_filename": "sample.wav", "audio_format": "WAV",
        "sample_rate_hz": "16000", "bit_depth": "16",
        "consent_transcription": "YES", "consent_research": "YES",
        "consent_public_release": "NO", "consent_commercial_training": "YES",
        "consent_voice_synthesis": "NO", "reviewer_1_status": "APPROVED",
        "reviewer_2_status": "APPROVED",
    })
    row.update(overrides)
    return row

class PawariDatasetValidatorTests(unittest.TestCase):
    def write_csv(self, path, rows):
        with path.open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=HEADERS)
            writer.writeheader()
            writer.writerows(rows)

    def test_valid_dual_review_consented_record_is_eligible(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "data.csv"
            self.write_csv(path, [sample()])
            result = validate_csv(path)
            self.assertTrue(result["ok"])
            self.assertEqual(result["training_eligible_count"], 1)

    def test_blank_consent_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "data.csv"
            self.write_csv(path, [sample(consent_commercial_training="")])
            result = validate_csv(path)
            self.assertFalse(result["ok"])
            self.assertTrue(any(e["code"] == "EMPTY_REQUIRED_FIELD" for e in result["errors"]))

    def test_profile_location_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "data.csv"
            self.write_csv(path, [sample(district="Seoni")])
            result = validate_csv(path)
            self.assertTrue(any(e["code"] == "PROFILE_LOCATION_MISMATCH" for e in result["errors"]))

    def test_one_pending_reviewer_blocks_training_eligibility(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "data.csv"
            self.write_csv(path, [sample(reviewer_2_status="PENDING")])
            result = validate_csv(path)
            self.assertTrue(result["ok"])
            self.assertEqual(result["training_eligible_count"], 0)

    def test_voice_synthesis_consent_is_not_required_for_asr_training(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "data.csv"
            self.write_csv(path, [sample(consent_voice_synthesis="NO")])
            result = validate_csv(path)
            self.assertTrue(result["ok"])
            self.assertEqual(result["training_eligible_count"], 1)

    def test_duplicate_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "data.csv"
            self.write_csv(path, [sample(), sample()])
            result = validate_csv(path)
            self.assertTrue(any(e["code"] == "DUPLICATE_RECORD_ID" for e in result["errors"]))

if __name__ == "__main__":
    unittest.main()
