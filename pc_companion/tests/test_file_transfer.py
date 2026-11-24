import unittest

from pc_companion.file_transfer import (
    FILE_CHUNK_BYTES,
    MAX_PC_FILE_BYTES,
    all_chunk_indexes,
    chunk_count_for_size,
    expected_chunk_bytes,
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


if __name__ == "__main__":
    unittest.main()
