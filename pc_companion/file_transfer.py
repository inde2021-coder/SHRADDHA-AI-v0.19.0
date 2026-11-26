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

def can_accept_incoming_transfer(active_transfers, transfer_id):
    """Allow only one new inbound transfer; existing IDs are handled by resume logic."""
    return (
        isinstance(active_transfers, dict) and not active_transfers and
        isinstance(transfer_id, str) and
        re.fullmatch(r"[A-Za-z0-9_-]{12,64}", transfer_id) is not None
    )


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


def publish_file_without_overwrite(part_path, destination_path):
    """Atomically publish a completed file without ever replacing user data.

    Both paths must be on the same filesystem. If hard-link publication is not
    supported, fail closed and leave the verified partial file for recovery.
    """
    from pathlib import Path

    part = Path(part_path)
    destination = Path(destination_path)
    if not part.is_file() or part.is_symlink() or destination.exists() or destination.is_symlink():
        raise FileExistsError("Destination already exists or partial file is unsafe.")
    # os.link is atomic and has create-new semantics on supported local filesystems.
    import os
    os.link(str(part), str(destination))
    part.unlink()

def save_outgoing_transfer_record(state_path, record):
    """Atomically persist the user-selected source and approved transfer manifest."""
    from pathlib import Path
    import json
    import os

    if not isinstance(record, dict):
        raise ValueError("Invalid outgoing transfer record.")
    transfer_id = record.get("transferId")
    size = record.get("size")
    digest = record.get("sha256")
    request_id = record.get("requestId")
    peer = record.get("peer")
    name = record.get("name")
    source = Path(record.get("path", ""))
    if (not isinstance(peer, str) or not peer or len(peer) > 128 or
            not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", request_id) or
            not isinstance(name, str) or not name or len(name) > 120 or Path(name).name != name or
            not valid_transfer_manifest(transfer_id, size, chunk_count_for_size(size), digest) or
            source.is_symlink() or not source.is_file() or source.stat().st_size != size or
            sha256_file(source) != digest):
        raise ValueError("Outgoing transfer source or manifest failed validation.")
    payload = {key: record[key] for key in ("peer", "path", "name", "size", "sha256", "requestId", "transferId")}
    target = Path(state_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix(".tmp")
    try:
        with temp.open("w", encoding="utf-8") as output:
            output.write(json.dumps(payload, separators=(",", ":")))
            output.flush()
            os.fsync(output.fileno())
        temp.replace(target)
    finally:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass
    return True


def load_outgoing_transfer_record(state_path):
    """Load a persisted sender record only when the original file still matches its hash."""
    from pathlib import Path
    import json

    try:
        raw = json.loads(Path(state_path).read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            return None
        transfer_id, peer, path_value = raw.get("transferId"), raw.get("peer"), raw.get("path")
        size, digest = raw.get("size"), raw.get("sha256")
        request_id, name = raw.get("requestId"), raw.get("name")
        if (not isinstance(peer, str) or not peer or len(peer) > 128 or
                not isinstance(path_value, str) or len(path_value) > 2048 or
                type(size) is not int or not valid_transfer_manifest(transfer_id, size, chunk_count_for_size(size), digest) or
                not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", request_id) or
                not isinstance(name, str) or not name or len(name) > 120 or Path(name).name != name):
            return None
        source = Path(path_value)
        if source.is_symlink() or not source.is_file() or source.stat().st_size != size or sha256_file(source) != digest:
            return None
        return {"peer": peer, "path": str(source), "name": name, "size": size,
                "sha256": digest, "requestId": request_id, "transferId": transfer_id,
                "send_queue": [], "in_flight": set(), "awaiting_approval": False}
    except (OSError, ValueError, TypeError, KeyError):
        return None

def save_completed_transfer_record(state_path, record):
    """Durably retain an incoming-transfer completion receipt across the ACK crash window."""
    from pathlib import Path
    import json
    import os
    import time

    if not isinstance(record, dict):
        raise ValueError("Invalid completed transfer receipt.")
    peer, transfer_id = record.get("peer"), record.get("transferId")
    destination, part_path = Path(record.get("path", "")), Path(record.get("partPath", ""))
    size, digest = record.get("size"), record.get("sha256")
    if (not isinstance(peer, str) or not peer or len(peer) > 128 or
            not isinstance(transfer_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", transfer_id) or
            type(size) is not int or not 1 <= size <= MAX_PC_FILE_BYTES or
            not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest) or
            destination.is_symlink() or part_path.is_symlink()):
        raise ValueError("Completed transfer receipt failed validation.")
    candidate = destination if destination.is_file() else part_path
    if not candidate.is_file() or candidate.stat().st_size != size or sha256_file(candidate) != digest:
        raise ValueError("Completed transfer file does not match its SHA-256 receipt.")
    target = Path(state_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        current = json.loads(target.read_text(encoding="utf-8"))
        if not isinstance(current, dict):
            current = {}
    except (OSError, ValueError):
        current = {}
    current = {
        key: value for key, value in current.items()
        if isinstance(key, str) and isinstance(value, dict) and
        type(value.get("completedAt")) is int
    }
    completed_at = record.get("completedAt") or int(time.time())
    if type(completed_at) is not int:
        raise ValueError("Invalid completion timestamp.")
    current[transfer_id] = {
        "peer": peer, "transferId": transfer_id, "path": str(destination),
        "partPath": str(part_path), "size": size, "sha256": digest,
        "completedAt": completed_at
    }
    # Keep a bounded, recent receipt ledger; receipts are idempotent ACK recovery.
    current = dict(sorted(current.items(), key=lambda item: item[1]["completedAt"])[-128:])
    temp = target.with_suffix(".tmp")
    try:
        with temp.open("w", encoding="utf-8") as output:
            output.write(json.dumps(current, separators=(",", ":")))
            output.flush()
            os.fsync(output.fileno())
        temp.replace(target)
    finally:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass
    return True


def load_completed_transfer_records(state_path, max_age_seconds=30 * 24 * 60 * 60):
    """Restore only verified completed files; finish a pending atomic publish if needed."""
    from pathlib import Path
    import json
    import time

    try:
        raw = json.loads(Path(state_path).read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            return {}
    except (OSError, ValueError):
        return {}
    now = int(time.time())
    restored = {}
    for transfer_id, item in raw.items():
        try:
            if (not isinstance(item, dict) or item.get("transferId") != transfer_id or
                    not re.fullmatch(r"[A-Za-z0-9_-]{12,64}", transfer_id) or
                    not isinstance(item.get("peer"), str) or not item["peer"] or len(item["peer"]) > 128 or
                    not isinstance(item.get("path"), str) or not 1 <= len(item["path"]) <= 4096 or
                    not isinstance(item.get("partPath"), str) or not 1 <= len(item["partPath"]) <= 4096 or
                    type(item.get("size")) is not int or
                    not isinstance(item.get("sha256"), str) or
                    not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]) or
                    type(item.get("completedAt")) is not int or
                    now - item["completedAt"] > max_age_seconds or item["completedAt"] > now + 300):
                continue
            destination = Path(item.get("path", ""))
            part_path = Path(item.get("partPath", ""))
            if destination.is_symlink() or part_path.is_symlink():
                continue
            candidate = destination if destination.is_file() else part_path
            if not candidate.is_file() or candidate.stat().st_size != item["size"] or sha256_file(candidate) != item["sha256"]:
                continue
            if candidate == part_path and not destination.exists():
                publish_file_without_overwrite(part_path, destination)
            if not destination.is_file() or destination.stat().st_size != item["size"] or sha256_file(destination) != item["sha256"]:
                continue
            restored[transfer_id] = {
                "peer": item["peer"], "transferId": transfer_id, "path": str(destination),
                "partPath": str(part_path), "size": item["size"], "sha256": item["sha256"],
                "completedAt": item["completedAt"]
            }
        except (OSError, ValueError, TypeError):
            continue
    return restored
