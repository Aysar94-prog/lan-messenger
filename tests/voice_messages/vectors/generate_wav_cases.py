"""Generate the canonical wav_cases.bin fixture source for I03.

Produces a single concatenated binary of deterministic WAV test cases. Each
case is a compact valid or malformed WAV file covering the 14 WAV validation
items from contract.md. The oversized 300 s case is a recipe-only entry: its
expected SHA-256 is recorded in the manifest but the 9.6 MB file is never
committed.

Usage:
    python generate_wav_cases.py [--out wav_cases.bin]
"""

from __future__ import annotations

import argparse
import hashlib
import struct
import sys
from pathlib import Path


def riff_chunk_size(data_bytes: int) -> int:
    """RIFF chunk size = 4 (WAVE) + 24 (fmt) + 8 (data header) + data_bytes."""
    return 4 + 24 + 8 + data_bytes


def build_wav_header(
    data_bytes: int,
    sample_rate: int = 16000,
    channels: int = 1,
    bits_per_sample: int = 16,
    audio_format: int = 1,  # 1 = PCM
) -> bytes:
    """Build a canonical 44-byte WAV header (RIFF + fmt + data chunk header)."""
    byte_rate = sample_rate * channels * (bits_per_sample // 8)
    block_align = channels * (bits_per_sample // 8)
    riff_size = riff_chunk_size(data_bytes)

    header = bytearray()
    # RIFF
    header.extend(b"RIFF")
    header.extend(struct.pack("<I", riff_size))
    header.extend(b"WAVE")
    # fmt chunk
    header.extend(b"fmt ")
    header.extend(struct.pack("<I", 16))  # PCM chunk size
    header.extend(struct.pack("<H", audio_format))
    header.extend(struct.pack("<H", channels))
    header.extend(struct.pack("<I", sample_rate))
    header.extend(struct.pack("<I", byte_rate))
    header.extend(struct.pack("<H", block_align))
    header.extend(struct.pack("<H", bits_per_sample))
    # data chunk header
    header.extend(b"data")
    header.extend(struct.pack("<I", data_bytes))
    return bytes(header)


def silent_pcm_samples(num_samples: int) -> bytes:
    """Return num_samples of silent (zero) 16-bit little-endian PCM."""
    return b"\x00\x00" * num_samples


# ---------------------------------------------------------------------------
# Case builders — each returns (case_id, bytes)
# ---------------------------------------------------------------------------

def case_valid_minimal() -> tuple[str, bytes]:
    """One-frame valid WAV: 16 kHz mono 16-bit PCM, 640 data bytes."""
    data = silent_pcm_samples(320)  # 320 samples * 2 bytes = 640
    header = build_wav_header(len(data))
    return "wav-valid-minimal", header + data


def case_empty() -> tuple[str, bytes]:
    """Empty file (0 bytes)."""
    return "wav-invalid-empty", b""


def case_bad_riff() -> tuple[str, bytes]:
    """First four bytes are 'XXXX' not 'RIFF'."""
    data = silent_pcm_samples(320)
    header = build_wav_header(len(data))
    bad = b"XXXX" + header[4:]
    return "wav-invalid-bad-riff", bad + data


def case_wrong_rate() -> tuple[str, bytes]:
    """Structurally valid WAV with 44100 Hz sample rate."""
    data = silent_pcm_samples(320)
    header = build_wav_header(len(data), sample_rate=44100)
    return "wav-invalid-wrong-rate", header + data


def case_truncated() -> tuple[str, bytes]:
    """Header declares 640 data bytes but file is shorter (only 320 bytes of data)."""
    data = silent_pcm_samples(160)  # 320 bytes, but header says 640
    header = build_wav_header(640)  # declares 640
    return "wav-invalid-truncated", header + data


def case_one_sample() -> tuple[str, bytes]:
    """Valid header but only 2 bytes of data (one sample, not a full frame)."""
    data = silent_pcm_samples(1)  # 2 bytes
    header = build_wav_header(len(data))
    return "wav-invalid-one-sample", header + data


def case_short_final_frame() -> tuple[str, bytes]:
    """642 data bytes: one full 640-byte frame + 2-byte short final frame."""
    data = silent_pcm_samples(321)  # 642 bytes
    header = build_wav_header(len(data))
    return "wav-invalid-short-final-frame", header + data


def case_bad_block_align() -> tuple[str, bytes]:
    """Block alignment is 4 instead of 2."""
    data = silent_pcm_samples(320)
    header = build_wav_header(len(data))
    # Patch block_align at offset 32 (2 bytes, little-endian)
    bad = bytearray(header)
    struct.pack_into("<H", bad, 32, 4)
    return "wav-invalid-bad-block-align", bytes(bad) + data


def case_unknown_chunk() -> tuple[str, bytes]:
    """Has a 'junk' chunk between fmt and data (item 11: unknown chunks skipped)."""
    data = silent_pcm_samples(320)
    junk_payload = b"\x01\x02\x03\x04\x05\x06\x07\x08"
    # Build header manually to insert junk chunk
    byte_rate = 16000 * 1 * 2
    block_align = 2
    junk_size = len(junk_payload)
    # RIFF size includes junk chunk: 4(WAVE) + 24(fmt) + 8(junk header) + junk_size + 8(data header) + data
    riff_size = 4 + 24 + 8 + junk_size + 8 + len(data)
    buf = bytearray()
    buf.extend(b"RIFF")
    buf.extend(struct.pack("<I", riff_size))
    buf.extend(b"WAVE")
    # fmt
    buf.extend(b"fmt ")
    buf.extend(struct.pack("<I", 16))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<I", 16000))
    buf.extend(struct.pack("<I", byte_rate))
    buf.extend(struct.pack("<H", block_align))
    buf.extend(struct.pack("<H", 16))
    # junk chunk (padded to even)
    buf.extend(b"junk")
    buf.extend(struct.pack("<I", junk_size))
    buf.extend(junk_payload)
    # data
    buf.extend(b"data")
    buf.extend(struct.pack("<I", len(data)))
    buf.extend(data)
    return "wav-invalid-unknown-chunk", bytes(buf)


def case_duplicate_chunk() -> tuple[str, bytes]:
    """Two 'fmt ' chunks (item 12: duplicate required chunks rejected)."""
    data = silent_pcm_samples(320)
    byte_rate = 16000 * 1 * 2
    block_align = 2
    # RIFF size: 4(WAVE) + 24(fmt1) + 24(fmt2) + 8(data header) + data
    riff_size = 4 + 24 + 24 + 8 + len(data)
    buf = bytearray()
    buf.extend(b"RIFF")
    buf.extend(struct.pack("<I", riff_size))
    buf.extend(b"WAVE")
    # fmt 1
    buf.extend(b"fmt ")
    buf.extend(struct.pack("<I", 16))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<I", 16000))
    buf.extend(struct.pack("<I", byte_rate))
    buf.extend(struct.pack("<H", block_align))
    buf.extend(struct.pack("<H", 16))
    # fmt 2 (duplicate)
    buf.extend(b"fmt ")
    buf.extend(struct.pack("<I", 16))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<I", 16000))
    buf.extend(struct.pack("<I", byte_rate))
    buf.extend(struct.pack("<H", block_align))
    buf.extend(struct.pack("<H", 16))
    # data
    buf.extend(b"data")
    buf.extend(struct.pack("<I", len(data)))
    buf.extend(data)
    return "wav-invalid-duplicate-chunk", bytes(buf)


def case_reordered_chunks() -> tuple[str, bytes]:
    """'data' chunk before 'fmt ' chunk (item 13: reordered chunks)."""
    data = silent_pcm_samples(320)
    byte_rate = 16000 * 1 * 2
    block_align = 2
    riff_size = 4 + 8 + len(data) + 24  # WAVE + data + fmt
    buf = bytearray()
    buf.extend(b"RIFF")
    buf.extend(struct.pack("<I", riff_size))
    buf.extend(b"WAVE")
    # data first (reordered)
    buf.extend(b"data")
    buf.extend(struct.pack("<I", len(data)))
    buf.extend(data)
    # fmt second
    buf.extend(b"fmt ")
    buf.extend(struct.pack("<I", 16))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<H", 1))
    buf.extend(struct.pack("<I", 16000))
    buf.extend(struct.pack("<I", byte_rate))
    buf.extend(struct.pack("<H", block_align))
    buf.extend(struct.pack("<H", 16))
    return "wav-invalid-reordered-chunks", bytes(buf)


def case_trailing_bytes() -> tuple[str, bytes]:
    """Extra bytes after the data chunk (item 14: trailing bytes)."""
    data = silent_pcm_samples(320)
    header = build_wav_header(len(data))
    trailing = b"\xde\xad\xbe\xef\xca\xfe"
    return "wav-invalid-trailing-bytes", header + data + trailing


def case_unsupported_format() -> tuple[str, bytes]:
    """Non-PCM audio format (audio_format = 2, ADPCM)."""
    data = silent_pcm_samples(320)
    header = build_wav_header(len(data), audio_format=2)
    return "wav-invalid-unsupported-format", header + data


# ---------------------------------------------------------------------------
# Oversized recipe (not included in binary)
# ---------------------------------------------------------------------------

def oversized_expected_hash() -> str:
    """Return the deterministic SHA-256 of a 9,600,044-byte max WAV.

    The file is: 44-byte canonical header + 9,600,000 zero PCM bytes.
    We compute the hash without writing 9.6 MB to disk by hashing in chunks.
    """
    data_bytes = 9_600_000
    header = build_wav_header(data_bytes)
    h = hashlib.sha256()
    h.update(header)
    # Hash zero data in 1 MB chunks
    chunk = b"\x00" * 1_000_000
    remaining = data_bytes
    while remaining > 0:
        take = min(len(chunk), remaining)
        h.update(chunk[:take])
        remaining -= take
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Case registry — order determines offsets in wav_cases.bin
# ---------------------------------------------------------------------------

CASES = (
    case_valid_minimal,
    case_empty,
    case_bad_riff,
    case_wrong_rate,
    case_truncated,
    case_one_sample,
    case_short_final_frame,
    case_bad_block_align,
    case_unknown_chunk,
    case_duplicate_chunk,
    case_reordered_chunks,
    case_trailing_bytes,
    case_unsupported_format,
)


def generate_binary(output_path: Path) -> dict:
    """Write wav_cases.bin and return a dict of {case_id: {offset, length, sha256}}."""
    entries: dict[str, dict] = {}
    offset = 0
    with output_path.open("wb") as f:
        for builder in CASES:
            case_id, data = builder()
            sha = hashlib.sha256(data).hexdigest()
            f.write(data)
            entries[case_id] = {
                "offset": offset,
                "length": len(data),
                "sha256": sha,
            }
            offset += len(data)
    return entries


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out", type=Path,
        default=Path(__file__).parent / "wav_cases.bin",
        help="output binary path (default: wav_cases.bin beside this script)",
    )
    args = parser.parse_args()

    entries = generate_binary(args.out)
    oversized_sha = oversized_expected_hash()

    print(f"Wrote {args.out} ({args.out.stat().st_size} bytes, "
          f"{len(entries)} cases)")
    print(f"Oversized recipe SHA-256: {oversized_sha}")
    print()
    for case_id, info in entries.items():
        print(f"  {case_id}: offset={info['offset']}, length={info['length']}, "
              f"sha256={info['sha256']}")


if __name__ == "__main__":
    main()