import unittest

from pc_companion.file_transfer import (
    FILE_CHUNK_BYTES,
    MAX_PC_FILE_BYTES,
    all_chunk_indexes,
    chunk_count_for_size,
    expected_chunk_bytes,
    take_chunk_window,
    validate_resume_indexes,
    valid_transfer_manifest,
    can_accept_incoming_transfer,
    requeue_rejected_chunk,
    prepare_retry_queue,
    sha256_file,
    save_outgoing_transfer_record,
    load_outgoing_transfer_record,
)


class FileTransferChunkGeometryTests(unittest.TestCase):
    def test_wire_chunk_size_matches_android_48_kib_protocol(self):
        self.assertEqual(FILE_CHUNK_BYTES, 48 * 1024)
        self.assertEqual(FILE_CHUNK_BYTES, 49152)

    def test_exact_chunk_boundary(self):
        self.assertEqual(chunk_count_for_size(49152), 1)
        self.assertEqual(list(all_chunk_indexes(49152)), [0])
        self.assertEqual(expected_chunk_bytes(49152, 1, 0), 49152)

    def test_one_byte_over_boundary_creates_two_chunks(self):
        self.assertEqual(chunk_count_for_size(49153), 2)
        self.assertEqual(expected_chunk_bytes(49153, 2, 0), 49152)
        self.assertEqual(expected_chunk_bytes(49153, 2, 1), 1)

    def test_multiple_chunks_have_exact_offsets_and_tail(self):
        size = 2 * FILE_CHUNK_BYTES + 37
        chunks = chunk_count_for_size(size)
        self.assertEqual(chunks, 3)
        self.assertEqual(
            [expected_chunk_bytes(size, chunks, i) for i in range(chunks)],
            [49152, 49152, 37],
        )

    def test_rejects_invalid_manifest_and_bounds(self):
        for size in (0, -1, MAX_PC_FILE_BYTES + 1, True, 1.5):
            with self.assertRaises(ValueError):
                chunk_count_for_size(size)
        with self.assertRaises(ValueError):
            expected_chunk_bytes(49153, 1, 0)
        with self.assertRaises(ValueError):
            expected_chunk_bytes(49153, 2, 2)


    def test_send_window_bounds_in_flight_chunks_and_refills_after_ack(self):
        queue = list(range(20))
        in_flight = set()
        first = take_chunk_window(queue, in_flight)
        self.assertEqual(first, list(range(8)))
        self.assertEqual(len(in_flight), 8)
        self.assertEqual(take_chunk_window(queue, in_flight), [])
        for index in first[:3]:
            in_flight.remove(index)
        second = take_chunk_window(queue, in_flight)
        self.assertEqual(second, [8, 9, 10])
        self.assertLessEqual(len(in_flight), 8)

    def test_send_window_rejects_invalid_limits_and_duplicate_indexes(self):
        with self.assertRaises(ValueError):
            take_chunk_window([1], set(), 0)
        with self.assertRaises(ValueError):
            take_chunk_window([1, 1], set(), 8)
        with self.assertRaises(ValueError):
            take_chunk_window([-1], set())
        with self.assertRaises(ValueError):
            take_chunk_window([3], set(), max_index=3)


    def test_resume_manifest_requires_exact_count_unique_integer_indexes(self):
        self.assertEqual(validate_resume_indexes([0, 2], 3, 3), [0, 2])
        self.assertEqual(validate_resume_indexes([], 3, 3), [])
        for missing, declared, expected in (
            ([0, 0], 3, 3), ([True], 3, 3), ([-1], 3, 3),
            ([3], 3, 3), ([0], 2, 3), ("0,1", 3, 3), ([0, 1, 2, 3], 3, 3)
        ):
            self.assertIsNone(validate_resume_indexes(missing, declared, expected))


    def test_incoming_manifest_requires_exact_integer_fields_and_safe_identifiers(self):
        transfer_id = "transfer_123456"
        digest = "a" * 64
        chunks = chunk_count_for_size(49153)
        self.assertTrue(valid_transfer_manifest(transfer_id, 49153, chunks, digest))
        for values in (
            (transfer_id, True, 1, digest),
            (transfer_id, 49153.0, chunks, digest),
            (transfer_id, "49153", chunks, digest),
            (transfer_id, 49153, True, digest),
            (transfer_id, 49153, "2", digest),
            ("../escape12345", 49153, chunks, digest),
            (transfer_id, 49153, chunks, "bad"),
        ):
            self.assertFalse(valid_transfer_manifest(*values))



class IncomingTransferAdmissionTests(unittest.TestCase):
    def test_only_one_new_incoming_transfer_is_admitted(self):
        self.assertTrue(can_accept_incoming_transfer({}, "transfer_123456"))
        self.assertFalse(can_accept_incoming_transfer({"existing_123456": {}}, "transfer_654321"))
        self.assertFalse(can_accept_incoming_transfer({}, "../bad"))
        self.assertFalse(can_accept_incoming_transfer([], "transfer_123456"))


class FileTransferNegativeAckTests(unittest.TestCase):
    def test_negative_ack_requeues_chunk_and_releases_window_slot(self):
        queue = [3, 4]
        in_flight = {1, 2}
        self.assertTrue(requeue_rejected_chunk(queue, in_flight, 2))
        self.assertEqual(queue, [2, 3, 4])
        self.assertEqual(in_flight, {1})
        self.assertFalse(requeue_rejected_chunk(queue, in_flight, 2))
        self.assertEqual(queue, [2, 3, 4])
        self.assertFalse(requeue_rejected_chunk(queue, in_flight, True))


class FileTransferRetryQueueTests(unittest.TestCase):
    def test_retry_queue_requeues_in_flight_chunks_without_duplicates(self):
        queue = [5, 1, 5]
        in_flight = {2, 1, 4}
        count = prepare_retry_queue(queue, in_flight)
        self.assertEqual(count, 4)
        self.assertEqual(queue, [1, 2, 4, 5])
        self.assertEqual(in_flight, set())

    def test_retry_queue_rejects_bool_and_invalid_indexes(self):
        with self.assertRaises(ValueError):
            prepare_retry_queue([True], set())
        with self.assertRaises(ValueError):
            prepare_retry_queue([-1], set())
        with self.assertRaises(ValueError):
            prepare_retry_queue([], [1])


class FileTransferStreamingHashTests(unittest.TestCase):
    def test_sha256_file_matches_standard_digest_without_loading_file_at_once(self):
        import hashlib
        import tempfile
        from pathlib import Path

        payload = (b"Shraddha-transfer-test-" * 10000) + b"end"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.bin"
            path.write_bytes(payload)
            self.assertEqual(sha256_file(path), hashlib.sha256(payload).hexdigest())


class OutgoingTransferRecoveryTests(unittest.TestCase):
    def test_sender_record_survives_restart_and_verifies_original_file(self):
        import hashlib
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "report.pdf"
            payload = b"shraddha outgoing transfer recovery"
            source.write_bytes(payload)
            record = {
                "peer": "paired-android-01", "path": str(source), "name": "report.pdf",
                "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
                "requestId": "request_123456", "transferId": "transfer_123456"
            }
            state = Path(directory) / "outgoing-transfer.json"
            self.assertTrue(save_outgoing_transfer_record(state, record))
            restored = load_outgoing_transfer_record(state)
            self.assertEqual(restored["transferId"], record["transferId"])
            self.assertEqual(restored["peer"], record["peer"])
            self.assertEqual(restored["path"], str(source))
            self.assertEqual(restored["send_queue"], [])
            self.assertEqual(restored["in_flight"], set())

    def test_sender_restore_rejects_changed_source_and_bad_manifest(self):
        import hashlib
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "report.pdf"
            source.write_bytes(b"original")
            record = {"peer": "paired-android-01", "path": str(source), "name": "report.pdf",
                      "size": 8, "sha256": hashlib.sha256(b"original").hexdigest(),
                      "requestId": "request_123456", "transferId": "transfer_123456"}
            state = Path(directory) / "outgoing-transfer.json"
            save_outgoing_transfer_record(state, record)
            source.write_bytes(b"tampered")
            self.assertIsNone(load_outgoing_transfer_record(state))
            record["name"] = "../escape.pdf"
            with self.assertRaises(ValueError):
                save_outgoing_transfer_record(state, record)


if __name__ == "__main__":
    unittest.main()
