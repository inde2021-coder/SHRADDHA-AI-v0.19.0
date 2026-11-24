"""Shared constants and validation for the Shraddha E2E file-transfer wire format."""

FILE_CHUNK_BYTES = 48 * 1024  # Must match Android DeviceFileTransferProtocol.CHUNK_BYTES.
MAX_PC_FILE_BYTES = 20_000_000


def chunk_count_for_size(size_bytes):
    if not isinstance(size_bytes, int) or isinstance(size_bytes, bool) or not 1 <= size_bytes <= MAX_PC_FILE_BYTES:
        raise ValueError("File size is outside the supported PC transfer range.")
    return (size_bytes + FILE_CHUNK_BYTES - 1) // FILE_CHUNK_BYTES


def expected_chunk_bytes(size_bytes, chunk_count, index):
    if not isinstance(size_bytes, int) or isinstance(size_bytes, bool):
        raise ValueError("Invalid file size.")
    if not isinstance(chunk_count, int) or isinstance(chunk_count, bool):
        raise ValueError("Invalid chunk count.")
    if not isinstance(index, int) or isinstance(index, bool):
        raise ValueError("Invalid chunk index.")
    if not 1 <= size_bytes <= MAX_PC_FILE_BYTES:
        raise ValueError("File size is outside the supported PC transfer range.")
    if chunk_count != chunk_count_for_size(size_bytes) or not 0 <= index < chunk_count:
        raise ValueError("Chunk manifest or index is invalid.")
    if index == chunk_count - 1:
        return size_bytes - index * FILE_CHUNK_BYTES
    return FILE_CHUNK_BYTES


def all_chunk_indexes(size_bytes):
    return range(chunk_count_for_size(size_bytes))
