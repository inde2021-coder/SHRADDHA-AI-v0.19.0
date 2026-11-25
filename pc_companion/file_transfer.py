import re
import hashlib

"""Shared constants and validation for the Shraddha E2E file-transfer wire format."""

FILE_CHUNK_BYTES = 48 * 1024  # Must match Android DeviceFileTransferProtocol.CHUNK_BYTES.
MAX_PC_FILE_BYTES = 100 * 1024 * 1024


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


MAX_IN_FLIGHT_CHUNKS = 8


def take_chunk_window(queue, in_flight, limit=MAX_IN_FLIGHT_CHUNKS, max_index=None):
    if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 64:
        raise ValueError("Chunk window must be between 1 and 64.")
    if max_index is not None and (not isinstance(max_index, int) or isinstance(max_index, bool) or max_index < 1):
        raise ValueError("Maximum chunk count must be a positive integer.")
    batch = []
    while queue and len(in_flight) < limit:
        index = queue.pop(0)
        if (not isinstance(index, int) or isinstance(index, bool) or index < 0 or
                (max_index is not None and index >= max_index) or index in in_flight):
            raise ValueError("Invalid or duplicate queued chunk index.")
        in_flight.add(index)
        batch.append(index)
    return batch


def validate_resume_indexes(missing, declared_chunk_count, expected_chunk_count):
    if (not isinstance(missing, list) or isinstance(declared_chunk_count, bool) or
            not isinstance(declared_chunk_count, int) or declared_chunk_count != expected_chunk_count or
            len(missing) > expected_chunk_count):
        return None
    if any(not isinstance(index, int) or isinstance(index, bool) or
           not 0 <= index < expected_chunk_count for index in missing):
        return None
    if len(set(missing)) != len(missing):
        return None
    return list(missing)


def valid_transfer_manifest(transfer_id, size_bytes, declared_chunk_count, sha256):
    if (not isinstance(transfer_id, str) or
            not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", transfer_id) or
            type(size_bytes) is not int or
            type(declared_chunk_count) is not int or
            not isinstance(sha256, str) or
            not re.fullmatch(r"[a-f0-9]{64}", sha256)):
        return False
    try:
        return declared_chunk_count == chunk_count_for_size(size_bytes)
    except ValueError:
        return False


def requeue_rejected_chunk(queue, in_flight, index):
    """Return a negatively acknowledged chunk to the front of the retry queue."""
    if (not isinstance(queue, list) or not isinstance(in_flight, set) or
            not isinstance(index, int) or isinstance(index, bool) or
            index not in in_flight):
        return False
    in_flight.remove(index)
    if index not in queue:
        queue.insert(0, index)
    return True


def prepare_retry_queue(queue, in_flight):
    """Move all queued/in-flight chunks into a deterministic retry batch."""
    if not isinstance(queue, list) or not isinstance(in_flight, set):
        raise ValueError("Invalid retry queue state")
    indexes = queue + list(in_flight)
    if any(not isinstance(index, int) or isinstance(index, bool) or index < 0 for index in indexes):
        raise ValueError("Invalid chunk index in retry state")
    retry = sorted(set(indexes))
    queue[:] = retry
    in_flight.clear()
    return len(retry)


def sha256_file(path):
    """Hash a file in bounded memory, independent of its total size."""
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class TransferPersistenceError(Exception):
    """A durable chunk write succeeded but its recovery bitmap was not committed."""
