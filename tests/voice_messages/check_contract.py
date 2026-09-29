"""Assert the frozen Voice Messages shared contracts satisfy their invariants.

Default mode checks tests/voice_messages/contract.md (I01): required sections,
frozen media/scheduler constants, receiver states, scheduler priority, the
nine scheduler rules, the non-goal list, and the contract version.

--pcm additionally checks tests/voice_messages/pcm-contract.md (I02), the
device-independent PCM frame and stream-event contract, after the I01 checks.

--vectors additionally checks tests/voice_messages/vectors/manifest.json (I03),
the canonical versioned shared fixture manifest: schema version, unique vector
IDs, declared categories, safe relative in-tree source paths, byte-length and
SHA-256 integrity of committed sources, non-null source enforcement for WAV
and PCM categories, PCM expectations validation (frame counts, events,
sequences, timestamps, epochs, callback plans), and rejection of
platform-local overrides.

--validation additionally cross-validates WAV vector expectations against the
actual fixture bytes: parses each committed WAV header and rejects any
expectation that contradicts the binary content (sample rate, channels, bit
depth, data size, total size, computed duration).

--mutation-self-test applies deletion/duplication/reorder mutations to
in-memory copies of the checked documents and requires the expected check
group to fail for every mutation, guarding the checker against vacuous
substring matches. When --vectors is active, vector-specific mutations are
also applied to the in-memory manifest. When --validation is active,
validation-specific mutations are applied.
"""
from __future__ import annotations

import argparse
import re
import struct
import sys
from pathlib import Path

CONTRACT_VERSION = 1
PCM_CONTRACT_VERSION = 1
MANIFEST_SCHEMA_VERSION = 1
VALIDATION_CONTRACT_VERSION = 1
MIN_RUNNER_SCHEMA = 1

# ---------------------------------------------------------------------------
# Deterministic max-boundary WAV recipe (never written to disk)
# ---------------------------------------------------------------------------
_MAX_RECIPE_NAME = "max-boundary"
_MAX_RECIPE_DATA_BYTES = 9_600_000
_MAX_RECIPE_BYTE_LENGTH = 44 + _MAX_RECIPE_DATA_BYTES  # 9,600,044


def _stream_compute_max_recipe_hash() -> str:
    """Independently compute the SHA-256 of the max-boundary 9,600,044-byte WAV.

    Canonical 44-byte RIFF header (16 kHz mono 16-bit PCM) followed by
    9,600,000 zero PCM bytes. Streamed in 1 MB chunks; the 9.6 MB file is
    never materialised.
    """
    import hashlib

    data_bytes = _MAX_RECIPE_DATA_BYTES
    sample_rate = 16000
    channels = 1
    bits_per_sample = 16
    audio_format = 1  # PCM

    byte_rate = sample_rate * channels * (bits_per_sample // 8)
    block_align = channels * (bits_per_sample // 8)
    riff_size = 4 + 24 + 8 + data_bytes  # WAVE + fmt(24) + data hdr(8) + data

    header = bytearray()
    header.extend(b"RIFF")
    header.extend(struct.pack("<I", riff_size))
    header.extend(b"WAVE")
    header.extend(b"fmt ")
    header.extend(struct.pack("<I", 16))  # PCM fmt chunk size
    header.extend(struct.pack("<H", audio_format))
    header.extend(struct.pack("<H", channels))
    header.extend(struct.pack("<I", sample_rate))
    header.extend(struct.pack("<I", byte_rate))
    header.extend(struct.pack("<H", block_align))
    header.extend(struct.pack("<H", bits_per_sample))
    header.extend(b"data")
    header.extend(struct.pack("<I", data_bytes))

    h_obj = hashlib.sha256()
    h_obj.update(bytes(header))
    # Hash zero data in 1 MB chunks
    zero_chunk = b"\x00" * 1_000_000
    remaining = data_bytes
    while remaining > 0:
        take = min(len(zero_chunk), remaining)
        h_obj.update(zero_chunk[:take])
        remaining -= take
    return h_obj.hexdigest()


_RECIPE_EXPECTED_HASH = _stream_compute_max_recipe_hash()

KNOWN_VECTOR_CATEGORIES = frozenset({"wav-validation", "pcm-frames", "seek", "marker", "receiver", "registry", "scheduler"})

# Keys that must not appear at vector or source level (platform-local overrides).
FORBIDDEN_PLATFORM_SUBSTRINGS = ("windows", "android")

REQUIRED_SECTIONS = (
    "Decisions",
    "Constants",
    "Receiver states",
    "Scheduler priority",
    "Non-goals",
    "Scheduler rules",
    "Draft recovery",
    "Transactional Send",
    "Seek procedure",
    "WAV validation",
    "Version",
)

REQUIRED_PCM_SECTIONS = (
    "Ownership and frames",
    "Fragments and odd bytes",
    "Epoch, sequence, and time",
    "Capacity and fail-stop",
    "Events",
    "Forbidden dependencies",
    "Version",
)

# (regex, human label) pairs that must match inside the Constants section.
REQUIRED_CONSTANTS = (
    (r"16000\s*Hz", "sample rate 16000 Hz"),
    (r"signed 16-bit PCM", "signed 16-bit PCM samples"),
    (r"\bmono\b", "mono channel"),
    (r"\b2 bytes\b", "2-byte block alignment"),
    (r"32000\s*bytes/s", "byte rate 32000 bytes/s"),
    (r"20\s*ms\s*=\s*320\s*samples\s*=\s*640\s*bytes", "20 ms / 320 sample / 640 byte frame"),
    (r"300\s*s\b", "300 s maximum recording duration"),
    (r"9,?600,?000\s*bytes", "9600000-byte PCM limit"),
    (r"9,?600,?044\s*bytes", "9600044-byte WAV limit"),
    (r"voice-<message-id>\.lanvoice\.wav", "marked filename pattern"),
    (r"\b10\b active or finalized drafts", "draft registry cap of 10"),
    (r"\b30 days\b", "30-day stale-review age"),
    (r"\b3\b consecutive automatic voice admissions", "3:1 voice/image fairness"),
)

# Receiver states that must each appear exactly once as the lead cell of a
# Receiver-states table row (exact, case-sensitive cell match, so prose that
# merely mentions a state name can never satisfy this check).
RECEIVER_STATES = (
    "Candidate",
    "Fetching",
    "Playable",
    "Invalid marked content",
    "Unavailable",
)

# Distinctive phrase(s) that must appear in each numbered scheduler rule.
SCHEDULER_RULE_KEYWORDS = (
    ("user-initiated", "first"),
    ("voice message", "next"),
    ("image", "last"),
    ("three consecutive automatic voice admissions", "image"),
    ("never paused or cancelled",),
    ("next eligible admission",),
    ("only on successful admission",),
    ("offline transition",),
    ("restart reconstructs",),
)

# Distinctive phrase(s) that must appear in each numbered non-goal.
NON_GOAL_KEYWORDS = (
    "echo cancellation",
    "waveform",
    "trimming",
    "playback-speed",
    "transcription",
    "codec",
    "before it has stopped",
    "signaling",
    "nat traversal",
    "video",
)

# Phrase(s) that must appear in each numbered scheduler-priority tier.
PRIORITY_TIERS = (
    ("user-initiated",),
    ("voice message",),
    ("image",),
)

# (check-group name, section title, per-item phrase tuples) for every PCM
# contract section whose numbered items carry frozen invariants.
PCM_SECTION_RULES = (
    ("pcm ownership", "Ownership and frames", (
        ("immutable", "owned"),
        ("640 even bytes",),
        ("nonempty and even",),
        ("16000 hz",),
    )),
    ("pcm fragments", "Fragments and odd bytes", (
        ("one pending odd byte",),
        ("assembled with the next available byte",),
        ("fatal",),
        ("bounded",),
    )),
    ("pcm epoch/time", "Epoch, sequence, and time", (
        ("sequence 0 upward",),
        ("62500", "wall-clock"),
        ("restart", "reset"),
        ("discontinuity",),
    )),
    ("pcm capacity", "Capacity and fail-stop", (
        ("eight frames / 5120 bytes", "never blocks"),
        ("one terminal failure",),
        ("invalid", "silently"),
        ("restart into a new epoch",),
        ("9600000",),
    )),
    ("pcm events", "Events", (
        ("end, discontinuity, devicefailure, and restart",),
        ("only normal termination",),
        ("terminal for its epoch",),
        ("only way to begin new recording",),
    )),
    ("pcm forbidden imports", "Forbidden dependencies", (
        ("platform, wav-parsing, lm4, attachment-store, ui, call-signaling, or video",),
        ("translate device callbacks into fragments and events",),
    )),
)


def section_span(text: str, title: str) -> tuple[int, int] | None:
    """Return the (start, end) span of a '## <title>' section body, or None."""
    match = re.search(rf"^## {re.escape(title)}\s*$", text, re.MULTILINE)
    if match is None:
        return None
    body_start = match.end()
    next_heading = re.search(r"^##\s", text[body_start:], re.MULTILINE)
    body_end = body_start + next_heading.start() if next_heading else len(text)
    return body_start, body_end


def section(text: str, title: str) -> str | None:
    """Return the body of a '## <title>' section, or None when absent."""
    span = section_span(text, title)
    return None if span is None else text[span[0]:span[1]]


def subsection(text: str, section_title: str, subsection_title: str) -> str | None:
    """Return the body of a '### <subsection_title>' within '## <section_title>'."""
    sec_body = section(text, section_title)
    if sec_body is None:
        return None
    match = re.search(rf"^### {re.escape(subsection_title)}\s*$", sec_body, re.MULTILINE)
    if match is None:
        return None
    body_start = match.end()
    remainder = sec_body[body_start:]
    next_sub = re.search(r"^###\s", remainder, re.MULTILINE)
    body_end = body_start + next_sub.start() if next_sub else len(sec_body)
    return sec_body[body_start:body_end]


def numbered_items(body: str) -> list[tuple[int, str]]:
    """Return (number, text) for each 'N. text' item, joining continuation lines.

    A continuation line is any non-empty line that does not start with a
    number-period sequence, a heading marker, or a table row.
    """
    items = []
    current_number = None
    current_lines = []
    for line in body.splitlines():
        match = re.match(r"^(\d+)\.\s+(\S.*)$", line)
        if match:
            if current_number is not None:
                items.append((current_number, " ".join(current_lines)))
            current_number = int(match.group(1))
            current_lines = [match.group(2)]
        elif current_number is not None and line.strip() and not line.strip().startswith(("#", "|")):
            current_lines.append(line.strip())
        else:
            if current_number is not None:
                items.append((current_number, " ".join(current_lines)))
            current_number = None
            current_lines = []
    if current_number is not None:
        items.append((current_number, " ".join(current_lines)))
    return items


def table_rows(body: str) -> list[list[str]]:
    """Return the cell lists of every Markdown table row in a section body."""
    rows = []
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith("|") and stripped.endswith("|") and len(stripped) > 1:
            rows.append([cell.strip() for cell in stripped[1:-1].split("|")])
    return rows


def is_separator_row(cells: list[str]) -> bool:
    """Return True for a Markdown table delimiter row such as '|---|---|'."""
    return bool(cells) and all(re.fullmatch(r":?-{2,}:?", cell) for cell in cells)


def table_data_rows(body: str) -> list[list[str]]:
    """Return the data rows of the section's table, skipping header/separator."""
    rows = table_rows(body)
    for index, cells in enumerate(rows):
        if is_separator_row(cells):
            return rows[index + 1:]
    return rows


def check_sections(text: str) -> list[str]:
    return [f"missing section '## {title}'" for title in REQUIRED_SECTIONS
            if section(text, title) is None]


def check_constants(text: str) -> list[str]:
    body = section(text, "Constants") or ""
    return [f"constant missing in Constants section: {label}"
            for pattern, label in REQUIRED_CONSTANTS
            if not re.search(pattern, body, re.IGNORECASE)]


def check_states(text: str) -> list[str]:
    body = section(text, "Receiver states") or ""
    lead_cells = [cells[0] for cells in table_data_rows(body) if cells]
    failures = []
    for state in RECEIVER_STATES:
        count = lead_cells.count(state)
        if count == 0:
            failures.append(f"receiver state missing: {state}")
        elif count > 1:
            failures.append(f"receiver state duplicated: {state}")
    return failures


def check_priority(text: str) -> list[str]:
    body = section(text, "Scheduler priority") or ""
    failures = []
    lowered = body.lower()
    if "non-preemptive" not in lowered or "next-admitted" not in lowered:
        failures.append("priority must state the fixed non-preemptive next-admitted policy")
    items = numbered_items(body)
    if [number for number, _ in items] != [1, 2, 3]:
        failures.append("priority must be a numbered list of exactly tiers 1..3")
        return failures
    for (number, tier_text), keywords in zip(items, PRIORITY_TIERS):
        lowered_tier = tier_text.lower()
        failures.extend(f"priority tier {number} lost required phrase: {keyword!r}"
                        for keyword in keywords if keyword not in lowered_tier)
    return failures


def check_rules(text: str) -> list[str]:
    body = section(text, "Scheduler rules") or ""
    items = numbered_items(body)
    if [number for number, _ in items] != list(range(1, 10)):
        return ["scheduler rules must be a numbered list of exactly rules 1..9"]
    failures = []
    for (number, rule), keywords in zip(items, SCHEDULER_RULE_KEYWORDS):
        lowered = rule.lower()
        failures.extend(f"scheduler rule {number} lost required phrase: {keyword!r}"
                        for keyword in keywords if keyword not in lowered)
    return failures


def make_section_item_check(title: str, item_keywords: tuple, label: str):
    """Build a check requiring a numbered 1..N list with per-item phrases.

    Anchoring each phrase to its own numbered item keeps the check structural:
    deleting or rewording an item fails even if the phrase occurs elsewhere in
    the section body.
    """
    def check(text: str) -> list[str]:
        body = section(text, title) or ""
        items = numbered_items(body)
        if [number for number, _ in items] != list(range(1, len(item_keywords) + 1)):
            return [f"{label} must be a numbered list of exactly items "
                    f"1..{len(item_keywords)}"]
        failures = []
        for (number, item_text), keywords in zip(items, item_keywords):
            lowered = item_text.lower()
            failures.extend(f"{label} item {number} lost required phrase: {keyword!r}"
                            for keyword in keywords if keyword not in lowered)
        return failures
    return check


check_non_goals = make_section_item_check("Non-goals", NON_GOAL_KEYWORDS, "non-goals")


def check_version(text: str) -> list[str]:
    versions = re.findall(r"^contract-version:\s*(\d+)\s*$", text, re.MULTILINE)
    if not versions:
        return ["missing 'contract-version: <n>' line"]
    if any(int(value) != CONTRACT_VERSION for value in versions):
        return [f"contract-version must be {CONTRACT_VERSION}"]
    if "contract-version:" not in (section(text, "Version") or ""):
        return ["Version section must restate contract-version"]
    return []


# Per-item keywords for Draft recovery > Registry rules (9 items).
REGISTRY_RULES_KEYWORDS = (
    ("application-private storage",),
    ("maximum 10",),
    ("blocks only creation",),
    ("stale-review", "30 days"),
    ("never silently deletes",),
    ("bound to its original conversation",),
    ("preview/delete-only",),
    ("never retargeted",),
    ("arbitrary external filesystem paths",),
)

# Per-item keywords for Draft recovery > Registry fields (10 items).
REGISTRY_FIELDS_KEYWORDS = (
    ("opaque draft id",),
    ("owning conversation identity",),
    ("conversation type",),
    ("created and updated timestamps",),
    ("application-private storage identity",),
    ("recording/finalization state",),
    ("expected audio format",),
    ("byte-size and duration metadata",),
    ("send-transaction association",),
    ("stale-review state",),
)

# Per-item keywords for Transactional Send > Ten-step durable write order (10 items).
TEN_STEP_KEYWORDS = (
    ("durably register draft ownership",),
    ("accepting microphone frames",),
    ("normalized pcm",),
    ("safely finalize the wav",),
    ("flush and validate",),
    ("mark the draft recoverable",),
    ("allocate the message id",),
    ("import the complete wav",),
    ("durably save the queued message",),
    ("best-effort delete plaintext",),
)

# Per-item keywords for Transactional Send > Crash-boundary reconciliation (3 items).
CRASH_OUTCOME_KEYWORDS = (
    ("valid recoverable draft",),
    ("durable queued message", "durable encrypted source"),
    ("diagnosed invalid entry",),
)


def _check_subsection_items(text: str, section_title: str, subsection_title: str,
                            keywords: tuple, label: str) -> list[str]:
    """Check that a '### <subsection_title>' has the expected numbered items."""
    sub_body = subsection(text, section_title, subsection_title)
    if sub_body is None:
        return [f"{label} missing '### {subsection_title}' subsection"]
    items = numbered_items(sub_body)
    if [number for number, _ in items] != list(range(1, len(keywords) + 1)):
        return [f"{label} must be a numbered list of exactly items "
                f"1..{len(keywords)}"]
    failures = []
    for (number, item_text), kwds in zip(items, keywords):
        lowered = item_text.lower()
        failures.extend(f"{label} item {number} lost required phrase: {kw!r}"
                        for kw in kwds if kw not in lowered)
    return failures


def check_draft_recovery(text: str) -> list[str]:
    """Verify Draft recovery section has both subsections with correct items."""
    failures = []
    failures.extend(_check_subsection_items(
        text, "Draft recovery", "Registry rules",
        REGISTRY_RULES_KEYWORDS, "registry rules"))
    failures.extend(_check_subsection_items(
        text, "Draft recovery", "Registry fields",
        REGISTRY_FIELDS_KEYWORDS, "registry fields"))
    return failures


def check_transactional_send(text: str) -> list[str]:
    """Verify Transactional Send section has both subsections with correct items."""
    failures = []
    failures.extend(_check_subsection_items(
        text, "Transactional Send", "Ten-step durable write order",
        TEN_STEP_KEYWORDS, "ten-step"))
    failures.extend(_check_subsection_items(
        text, "Transactional Send", "Crash-boundary reconciliation",
        CRASH_OUTCOME_KEYWORDS, "crash-boundary"))
    # Also verify the anti-duplicate-send prose after the numbered list.
    body = section(text, "Transactional Send") or ""
    if "never produce a duplicate send" not in body.lower():
        failures.append("transactional send missing anti-duplicate-send guarantee")
    return failures


# Per-item keywords for the seven-step seek procedure.
SEEK_STEP_KEYWORDS = (
    ("clamp",),
    ("convert time", "checked arithmetic"),
    ("byte position", "validated wav"),
    ("align downward", "two-byte sample"),
    ("invalidate output",),
    ("reset playback",),
    ("effective aligned position",),
)

# Per-item keywords for the 14 WAV validation items.
WAV_VALIDATION_KEYWORDS = (
    ("riff", "wave"),
    ("chunk-offset", "overflow"),
    ("supported pcm",),
    ("one channel", "16 khz", "16 bits"),
    ("block alignment", "byte rate"),
    ("two-byte sample alignment",),
    ("bounded data chunk",),
    ("truncation", "out-of-range"),
    ("maximum duration", "stored-size"),
    ("duration consistency",),
    ("unknown chunks", "riff padding"),
    ("duplicate", "rejected"),
    ("reordered", "i04"),
    ("trailing bytes", "optional chunks"),
)


check_seek_procedure = make_section_item_check(
    "Seek procedure", SEEK_STEP_KEYWORDS, "seek procedure")

check_wav_validation = make_section_item_check(
    "WAV validation", WAV_VALIDATION_KEYWORDS, "wav validation")


CHECKS = (
    ("sections", check_sections),
    ("constants", check_constants),
    ("receiver states", check_states),
    ("scheduler priority", check_priority),
    ("scheduler rules", check_rules),
    ("non-goals", check_non_goals),
    ("draft recovery", check_draft_recovery),
    ("transactional send", check_transactional_send),
    ("seek procedure", check_seek_procedure),
    ("wav validation", check_wav_validation),
    ("version", check_version),
)


def check_pcm_sections(text: str) -> list[str]:
    return [f"missing section '## {title}'" for title in REQUIRED_PCM_SECTIONS
            if section(text, title) is None]


def check_pcm_version(text: str) -> list[str]:
    versions = re.findall(r"^pcm-contract-version:\s*(\d+)\s*$", text, re.MULTILINE)
    if not versions:
        return ["missing 'pcm-contract-version: <n>' line"]
    if any(int(value) != PCM_CONTRACT_VERSION for value in versions):
        return [f"pcm-contract-version must be {PCM_CONTRACT_VERSION}"]
    if "pcm-contract-version:" not in (section(text, "Version") or ""):
        return ["Version section must restate pcm-contract-version"]
    if not re.search(r"contract\.md", text) or not re.search(r"contract-version\s*1\b", text):
        return ["pcm contract must declare compatibility with contract.md (contract-version 1)"]
    return []


PCM_CHECKS = (
    (("pcm sections", check_pcm_sections),)
    + tuple((group, make_section_item_check(title, keywords, group))
            for group, title, keywords in PCM_SECTION_RULES)
    + (("pcm version", check_pcm_version),)
)

# --- I04 validation contract checks ---

REQUIRED_VALIDATION_SECTIONS = (
    "Marker recognition",
    "WAV format bounds",
    "Error precedence",
    "Seek procedure",
    "Receiver state and fallback",
    "Draft state recovery",
    "Ten-step transaction and crash outcomes",
    "Scheduler admission rules",
    "Contradiction resolution",
    "Version",
)

VALIDATION_MARKER_KEYWORDS = (
    ("voice-<message-id>.lanvoice.wav",),
    ("must not open, stat, or read",),
    ("case-sensitive",),
    ("normal encrypted store",),
    ("does not match the marker pattern",),
    ("mismatched marker",),
    ("separate from content validation",),
    ("never grants playback capability",),
)

VALIDATION_WAV_BOUNDS_KEYWORDS = (
    ("riff", "wave"),
    ("checked operations", "overflow"),
    ("audio format 1", "16000 hz", "16 bits"),
    ("data chunk", "non-empty"),
    ("unknown chunks", "padding"),
    ("exactly once",),
    ("even number of bytes",),
    ("9600044 bytes",),
    ("streaming-bounded",),
)

VALIDATION_ERROR_PRECEDENCE_KEYWORDS = (
    ("existence and readability",),
    ("too short",),
    ("riff or wave identifiers",),
    ("overflowing chunk-offset",),
    ("duplicate required chunks",),
    ("missing required chunks",),
    ("unsupported audio format",),
    ("incorrect sample rate",),
    ("incorrect block alignment",),
    ("data chunk truncation",),
    ("odd-byte data chunk",),
    ("duration exceeds maximum",),
    ("duration inconsistency",),
    ("trailing bytes",),
)

VALIDATION_SEEK_KEYWORDS = (
    ("clamp the requested time",),
    ("convert the clamped time", "checked integer arithmetic"),
    ("byte position", "data start offset"),
    ("align the byte position downward",),
    ("invalidate any output buffers",),
    ("reset playback sequence",),
    ("effective aligned byte position",),
)

VALIDATION_RECEIVER_KEYWORDS = (
    ("candidate transitions to playable",),
    ("invalid marked content", "ordinary attachment card"),
    ("unavailable", "expiry"),
    ("fetching is an internal retrieval state",),
    ("save/export is a separate user action",),
    ("voice retry and resume",),
)

VALIDATION_DRAFT_RECOVERY_KEYWORDS = (
    ("exactly one of three outcomes",),
    ("valid recoverable draft",),
    ("durable queued message",),
    ("diagnosed invalid entry",),
    ("never produce a duplicate",),
    ("encrypted source has been deleted",),
    ("unreachable valid draft",),
)

VALIDATION_TEN_STEP_KEYWORDS = (
    ("durably register draft ownership", "idempotent"),
    ("start accepting microphone frames", "step 1"),
    ("write normalized pcm", "exclusive write-lock"),
    ("safely finalize the wav",),
    ("flush and validate", "unflushed"),
    ("mark the draft recoverable",),
    ("allocate the message id",),
    ("import the complete", "encrypted normal store"),
    ("durably save the queued message",),
    ("best-effort delete", "step 9"),
)

VALIDATION_CRASH_KEYWORDS = (
    ("crash after step 1 before step 2",),
    ("crash after step 3 before step 4",),
    ("crash after step 4 before step5",),
    ("crash after step5 before step 6",),
    ("crash after step 6 before step7",),
    ("crash after step7 before step8",),
    ("crash after step8 before step9",),
    ("crash after step9 before step10",),
)

VALIDATION_SCHEDULER_KEYWORDS = (
    ("user-initiated",),
    ("voice message retrieval",),
    ("image retrieval",),
    ("three consecutive",),
    ("never paused or cancelled",),
    ("next eligible admission",),
    ("only on successful admission",),
    ("offline transition",),
    ("restart reconstructs",),
)

VALIDATION_CONTRADICTION_KEYWORDS = (
    ("reordered required chunks",),
    ("seek to end", "completed state"),
    ("trailing bytes and optional chunks", "riff"),
    ("short final frame", "nonempty even"),
    ("maximum duration and maximum file size",),
)


def check_validation_sections(text: str) -> list[str]:
    return [f"missing section '## {title}'" for title in REQUIRED_VALIDATION_SECTIONS
            if section(text, title) is None]


def _check_validation_subsection_items(text: str, section_title: str,
                                       subsection_title: str,
                                       keywords: tuple, label: str) -> list[str]:
    """Check that a '### <subsection_title>' has the expected numbered items."""
    sub_body = subsection(text, section_title, subsection_title)
    if sub_body is None:
        return [f"{label} missing '### {subsection_title}' subsection"]
    items = numbered_items(sub_body)
    if [number for number, _ in items] != list(range(1, len(keywords) + 1)):
        return [f"{label} must be a numbered list of exactly items "
                f"1..{len(keywords)}"]
    failures = []
    for (number, item_text), kwds in zip(items, keywords):
        lowered = item_text.lower()
        failures.extend(f"{label} item {number} lost required phrase: {kw!r}"
                        for kw in kwds if kw not in lowered)
    return failures


def check_validation_version(text: str) -> list[str]:
    versions = re.findall(r"^validation-contract-version:\s*(\d+)\s*$", text, re.MULTILINE)
    if not versions:
        return ["missing 'validation-contract-version: <n>' line"]
    if any(int(value) != VALIDATION_CONTRACT_VERSION for value in versions):
        return [f"validation-contract-version must be {VALIDATION_CONTRACT_VERSION}"]
    body = section(text, "Version") or ""
    if "validation-contract-version:" not in body:
        return ["Version section must restate validation-contract-version"]
    lowered = text.lower()
    failures = []
    if "contract.md" not in lowered or "contract-version 1" not in lowered:
        failures.append("validation contract must declare compatibility with contract.md (contract-version 1)")
    if "pcm-contract.md" not in lowered or "pcm-contract-version 1" not in lowered:
        failures.append("validation contract must declare compatibility with pcm-contract.md (pcm-contract-version 1)")
    return failures


def check_validation_ten_step_and_crash(text: str) -> list[str]:
    failures = []
    failures.extend(_check_validation_subsection_items(
        text, "Ten-step transaction and crash outcomes",
        "Ten-step durable write order", VALIDATION_TEN_STEP_KEYWORDS, "validation ten-step"))
    failures.extend(_check_validation_subsection_items(
        text, "Ten-step transaction and crash outcomes",
        "Crash-boundary reconciliation outcomes", VALIDATION_CRASH_KEYWORDS, "validation crash-boundary"))
    return failures


def check_validation_scheduler_and_subsections(text: str) -> list[str]:
    failures = []
    failures.extend(make_section_item_check(
        "Scheduler admission rules", VALIDATION_SCHEDULER_KEYWORDS, "validation scheduler")(text))
    for sub_name in ("Fairness counter semantics", "Restart reconstruction semantics"):
        sub_body = subsection(text, "Scheduler admission rules", sub_name)
        if sub_body is None:
            failures.append(f"validation scheduler missing '### {sub_name}' subsection")
    return failures


def check_validation_seek_and_subsections(text: str) -> list[str]:
    failures = []
    failures.extend(make_section_item_check(
        "Seek procedure", VALIDATION_SEEK_KEYWORDS, "validation seek")(text))
    for sub_name in ("Seek-to-end vs. completed-state resolution", "Alignment precedence"):
        sub_body = subsection(text, "Seek procedure", sub_name)
        if sub_body is None:
            failures.append(f"validation seek missing '### {sub_name}' subsection")
    return failures


VALIDATION_CONTRACT_CHECKS = (
    ("validation sections", check_validation_sections),
    ("validation marker", make_section_item_check(
        "Marker recognition", VALIDATION_MARKER_KEYWORDS, "validation marker")),
    ("validation wav bounds", make_section_item_check(
        "WAV format bounds", VALIDATION_WAV_BOUNDS_KEYWORDS, "validation wav bounds")),
    ("validation error precedence", make_section_item_check(
        "Error precedence", VALIDATION_ERROR_PRECEDENCE_KEYWORDS, "validation error precedence")),
    ("validation seek", check_validation_seek_and_subsections),
    ("validation receiver", make_section_item_check(
        "Receiver state and fallback", VALIDATION_RECEIVER_KEYWORDS, "validation receiver")),
    ("validation draft recovery", make_section_item_check(
        "Draft state recovery", VALIDATION_DRAFT_RECOVERY_KEYWORDS, "validation draft recovery")),
    ("validation ten-step + crash", check_validation_ten_step_and_crash),
    ("validation scheduler", check_validation_scheduler_and_subsections),
    ("validation contradiction", make_section_item_check(
        "Contradiction resolution", VALIDATION_CONTRADICTION_KEYWORDS, "validation contradiction")),
    ("validation version", check_validation_version),
)

CHECK_SETS = {"contract": CHECKS, "pcm": PCM_CHECKS, "validation": VALIDATION_CONTRACT_CHECKS}


def drop_section_line(title: str, prefix: str):
    """Build a mutation deleting the first '## <title>' line with the prefix."""
    def transform(text: str) -> str | None:
        span = section_span(text, title)
        if span is None:
            return None
        start, end = span
        lines = text[start:end].splitlines(keepends=True)
        for index, line in enumerate(lines):
            if line.strip().startswith(prefix):
                del lines[index]
                return text[:start] + "".join(lines) + text[end:]
        return None
    return transform


def duplicate_section_line(title: str, prefix: str):
    """Build a mutation inserting a copy of the first matching section line."""
    def transform(text: str) -> str | None:
        span = section_span(text, title)
        if span is None:
            return None
        start, end = span
        lines = text[start:end].splitlines(keepends=True)
        for index, line in enumerate(lines):
            if line.strip().startswith(prefix):
                lines.insert(index + 1, line)
                return text[:start] + "".join(lines) + text[end:]
        return None
    return transform


def swap_section_lines(title: str, prefix_a: str, prefix_b: str):
    """Build a mutation swapping two matching lines inside '## <title>'."""
    def transform(text: str) -> str | None:
        span = section_span(text, title)
        if span is None:
            return None
        start, end = span
        lines = text[start:end].splitlines(keepends=True)
        found = []
        for prefix in (prefix_a, prefix_b):
            index = next((i for i, line in enumerate(lines)
                          if line.strip().startswith(prefix)), None)
            if index is None:
                return None
            found.append(index)
        first, second = found
        lines[first], lines[second] = lines[second], lines[first]
        return text[:start] + "".join(lines) + text[end:]
    return transform


# (name, document key, expected failing check group, transform). Every
# mutation must flip its expected check group to failing on the mutated copy;
# a mutation whose target line is missing is itself a failure so the harness
# can never pass vacuously after contract drift.
MUTATIONS = (
    ("delete Candidate receiver-state row", "contract", "receiver states",
     drop_section_line("Receiver states", "| Candidate |")),
    ("delete Fetching receiver-state row", "contract", "receiver states",
     drop_section_line("Receiver states", "| Fetching |")),
    ("delete Playable receiver-state row", "contract", "receiver states",
     drop_section_line("Receiver states", "| Playable |")),
    ("delete Invalid marked content receiver-state row", "contract", "receiver states",
     drop_section_line("Receiver states", "| Invalid marked content |")),
    ("delete Unavailable receiver-state row", "contract", "receiver states",
     drop_section_line("Receiver states", "| Unavailable |")),
    ("duplicate Playable receiver-state row", "contract", "receiver states",
     duplicate_section_line("Receiver states", "| Playable |")),
    ("delete priority tier 1 (user-initiated)", "contract", "scheduler priority",
     drop_section_line("Scheduler priority", "1. User-initiated")),
    ("delete priority tier 2 (voice)", "contract", "scheduler priority",
     drop_section_line("Scheduler priority", "2. Automatic Voice Message")),
    ("delete priority tier 3 (image)", "contract", "scheduler priority",
     drop_section_line("Scheduler priority", "3. Automatic image")),
    ("swap priority tiers 2 and 3", "contract", "scheduler priority",
     swap_section_lines("Scheduler priority",
                        "2. Automatic Voice Message", "3. Automatic image")),
    ("delete non-goal 1 (echo cancellation)", "contract", "non-goals",
     drop_section_line("Non-goals", "1. Echo cancellation")),
    ("delete non-goal 6 (codec)", "contract", "non-goals",
     drop_section_line("Non-goals", "6. New audio codecs")),
    ("delete non-goal 10 (video)", "contract", "non-goals",
     drop_section_line("Non-goals", "10. Video capture")),
    ("delete PCM ownership item 1", "pcm", "pcm ownership",
     drop_section_line("Ownership and frames", "1. Frames are transport-independent")),
    ("delete PCM capacity item 1 (eight frames)", "pcm", "pcm capacity",
     drop_section_line("Capacity and fail-stop", "1. Pending capacity")),
    ("delete PCM events item 1 (event set)", "pcm", "pcm events",
     drop_section_line("Events", "1. The explicit stream events")),
    ("delete PCM forbidden-dependency item 1", "pcm", "pcm forbidden imports",
     drop_section_line("Forbidden dependencies", "1. Transport-independent PCM")),
    ("delete PCM fragments item 1 (callback boundaries)", "pcm", "pcm fragments",
     drop_section_line("Fragments and odd bytes", "1. Arbitrary callback fragment boundaries")),
    ("delete PCM epoch/time item 2 (timestamp 62500)", "pcm", "pcm epoch/time",
     drop_section_line("Epoch, sequence, and time", "2. A frame's timestampNs")),
    ("delete PCM epoch/time item 3 (restart resets)", "pcm", "pcm epoch/time",
     drop_section_line("Epoch, sequence, and time", "3. Restart closes the current epoch")),
    ("duplicate PCM capacity item 2 (overflow failure)", "pcm", "pcm capacity",
     duplicate_section_line("Capacity and fail-stop", "2. Overflow discards")),
    ("swap PCM ownership items 2 and 3", "pcm", "pcm ownership",
     swap_section_lines("Ownership and frames", "2. A frame carries", "3. The final frame")),
    ("delete draft recovery registry rule 1", "contract", "draft recovery",
     drop_section_line("Draft recovery", "1. The durable registry resides")),
    ("delete transactional send step 1", "contract", "transactional send",
     drop_section_line("Transactional Send", "1. Create and durably register")),
    ("delete seek procedure step 1", "contract", "seek procedure",
     drop_section_line("Seek procedure", "1. Clamp the requested time")),
    ("delete wav validation item 1", "contract", "wav validation",
     drop_section_line("WAV validation", "1. RIFF and WAVE identifiers")),
)


def run_mutation_self_test(documents: dict[str, str]) -> list[str]:
    """Require every active mutation to flip its expected check group."""
    failures = []
    for doc_key, text in documents.items():
        baseline = [f"{group}: {message}" for group, check in CHECK_SETS[doc_key]
                    for message in check(text)]
        failures.extend(f"{doc_key} baseline check failed: {message}"
                        for message in baseline)
    if failures:
        return failures
    for name, doc_key, expected_group, transform in MUTATIONS:
        if doc_key not in documents:
            continue
        mutated = transform(documents[doc_key])
        if mutated is None:
            failures.append(f"mutation {name!r}: target line not found "
                            f"in {doc_key} document")
            continue
        group_failures = {group: check(mutated) for group, check in CHECK_SETS[doc_key]}
        if not group_failures.get(expected_group):
            failures.append(f"mutation {name!r} still passes; expected check "
                            f"group {expected_group!r} to fail")
    return failures


# --- I04 validation contract mutation self-test ---

def _mutate_delete_validation_section(title: str, prefix: str):
    """Build a mutation deleting the first matching line in a validation contract section."""
    def transform(text: str) -> str | None:
        span = section_span(text, title)
        if span is None:
            return None
        start, end = span
        lines = text[start:end].splitlines(keepends=True)
        for index, line in enumerate(lines):
            if line.strip().startswith(prefix):
                del lines[index]
                return text[:start] + "".join(lines) + text[end:]
        return None
    return transform


VALIDATION_CONTRACT_MUTATIONS = (
    ("delete validation marker item 1", "validation", "validation marker",
     _mutate_delete_validation_section("Marker recognition", "1. A candidate voice-message")),
    ("delete validation wav bounds item 1", "validation", "validation wav bounds",
     _mutate_delete_validation_section("WAV format bounds", "1. Every candidate must")),
    ("delete validation error precedence item 1", "validation", "validation error precedence",
     _mutate_delete_validation_section("Error precedence", "1. File existence and")),
    ("delete validation seek item 1", "validation", "validation seek",
     _mutate_delete_validation_section("Seek procedure", "1. Clamp the requested time")),
    ("delete validation receiver item 1", "validation", "validation receiver",
     _mutate_delete_validation_section("Receiver state and fallback", "1. A Candidate transitions to")),
    ("delete validation draft recovery item 1", "validation", "validation draft recovery",
     _mutate_delete_validation_section("Draft state recovery", "1. At every crash boundary")),
    ("delete validation ten-step item 1", "validation", "validation ten-step + crash",
     _mutate_delete_validation_section("Ten-step transaction and crash outcomes", "1. Step 1: create and durably")),
    ("delete validation scheduler item 1", "validation", "validation scheduler",
     _mutate_delete_validation_section("Scheduler admission rules", "1. User-initiated attachment")),
    ("delete validation contradiction item 1", "validation", "validation contradiction",
     _mutate_delete_validation_section("Contradiction resolution", "1. WAV validation item 13")),
    ("delete validation contradiction item 3 (trailing)", "validation", "validation contradiction",
     _mutate_delete_validation_section("Contradiction resolution", "3. Trailing bytes and optional chunks")),
    ("delete validation contradiction item 4 (short final)", "validation", "validation contradiction",
     _mutate_delete_validation_section("Contradiction resolution", "4. Short final frame vs. data validation")),
)


def run_validation_contract_mutation_self_test(document: str) -> list[str]:
    failures = []
    baseline = [f"validation {group}: {message}"
               for group, check in VALIDATION_CONTRACT_CHECKS
                for message in check(document)]
    failures.extend(f"validation baseline check failed: {message}"
                    for message in baseline)
    if failures:
        return failures

    for name, doc_key, expected_group, transform in VALIDATION_CONTRACT_MUTATIONS:
        if doc_key != "validation":
            continue
        mutated = transform(document)
        if mutated is None:
            failures.append(f"validation mutation {name!r}: target line not found in document")
            continue
        group_failures = {group: check(mutated) for group, check in VALIDATION_CONTRACT_CHECKS}
        if not group_failures.get(expected_group):
            failures.append(f"validation mutation {name!r} still passes; expected check "
                           f"group {expected_group!r} to fail")
    return failures


# --- I03 vector manifest checks ---

def load_manifest(path: Path) -> dict:
    """Load and parse the vector manifest JSON file."""
    import json as _json
    try:
        return _json.loads(path.read_text(encoding="utf-8"))
    except (OSError, _json.JSONDecodeError) as exc:
        raise SystemExit(f"FAIL: cannot read manifest at {path}: {exc}")


def check_manifest_schema_version(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Enforce the five canonical fixture manifest version pins."""
    failures = []
    pins = (
        ("schema_version", MANIFEST_SCHEMA_VERSION),
        ("contract_version", CONTRACT_VERSION),
        ("pcm_contract_version", PCM_CONTRACT_VERSION),
        ("validation_contract_version", VALIDATION_CONTRACT_VERSION),
        ("min_runner_schema", MIN_RUNNER_SCHEMA),
    )
    for key, expected in pins:
        value = manifest.get(key)
        if value is None:
            failures.append(f"manifest missing required version pin {key!r}")
        elif not isinstance(value, int):
            failures.append(f"manifest {key} must be an integer, got {type(value).__name__} {value!r}")
        elif value != expected:
            failures.append(f"manifest {key} must be {expected}, got {value!r}")
    # min_runner_schema compatibility: reject unsupported runner schema
    runner = manifest.get("min_runner_schema")
    if isinstance(runner, int) and runner > MIN_RUNNER_SCHEMA:
        failures.append(f"manifest min_runner_schema {runner} exceeds supported {MIN_RUNNER_SCHEMA}; "
                        f"runner schema incompatible")
    return failures


def check_manifest_structure(manifest: dict, _manifest_dir: Path) -> list[str]:
    failures = []
    for key in ("schema_version", "categories", "vectors"):
        if key not in manifest:
            failures.append(f"manifest missing required key {key!r}")
    if not isinstance(manifest.get("vectors"), list):
        failures.append("manifest 'vectors' must be a list")
    if not isinstance(manifest.get("categories"), dict):
        failures.append("manifest 'categories' must be a dict")
    return failures


def check_vector_ids_unique(manifest: dict, _manifest_dir: Path) -> list[str]:
    seen: dict[str, int] = {}
    failures = []
    for index, vector in enumerate(manifest.get("vectors", [])):
        vid = vector.get("id")
        if vid is None:
            failures.append(f"vector at index {index} missing 'id'")
        elif not isinstance(vid, str):
            failures.append(f"vector at index {index}: 'id' must be a string, got {type(vid).__name__}")
        elif vid in seen:
            failures.append(f"duplicate vector id {vid!r} at indices {seen[vid]} and {index}")
        else:
            seen[vid] = index
    return failures


def check_vector_categories(manifest: dict, _manifest_dir: Path) -> list[str]:
    declared = set(manifest.get("categories", {}).keys())
    failures = []
    for index, vector in enumerate(manifest.get("vectors", [])):
        cat = vector.get("category")
        if cat is None:
            failures.append(f"vector at index {index} missing 'category'")
        elif cat not in declared:
            failures.append(f"vector {vector.get('id', index)!r} has undeclared category {cat!r}")
    return failures


def check_category_coverage(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Require every known category to be declared and used by at least one vector."""
    failures = []
    declared = set(manifest.get("categories", {}).keys())
    used = {v.get("category") for v in manifest.get("vectors", []) if v.get("category")}

    # Every known category must be declared
    for cat in sorted(KNOWN_VECTOR_CATEGORIES):
        if cat not in declared:
            failures.append(f"required category {cat!r} not declared in manifest categories")

    # Every declared category must be known
    for cat in sorted(declared):
        if cat not in KNOWN_VECTOR_CATEGORIES:
            failures.append(f"unknown category {cat!r} declared in manifest; known: {sorted(KNOWN_VECTOR_CATEGORIES)}")

    # Every known category must be used by at least one vector
    for cat in sorted(KNOWN_VECTOR_CATEGORIES):
        if cat not in used:
            failures.append(f"required category {cat!r} has no vectors")

    return failures


def _is_safe_relative_path(path_str: str, manifest_dir: Path) -> bool:
    """Return True if path_str is a safe relative path within manifest_dir."""
    if not path_str or path_str.startswith("/") or path_str.startswith("\\"):
        return False
    if ".." in Path(path_str).parts:
        return False
    try:
        resolved = (manifest_dir / path_str).resolve()
        resolved.relative_to(manifest_dir.resolve())
    except (ValueError, OSError):
        return False
    return True


def check_vector_paths(manifest: dict, manifest_dir: Path) -> list[str]:
    failures = []
    for vector in manifest.get("vectors", []):
        source = vector.get("source")
        if source is None:
            continue
        if not isinstance(source, dict):
            failures.append(f"vector {vector.get('id', '?')!r}: 'source' must be null or a dict")
            continue
        path_str = source.get("path")
        if path_str is None:
            continue
        if not isinstance(path_str, str):
            failures.append(f"vector {vector.get('id', '?')!r}: source.path must be a string")
            continue
        if "\\" in path_str:
            failures.append(f"vector {vector.get('id', '?')!r}: source.path {path_str!r} "
                            f"uses backslashes; use forward slashes")
        if not _is_safe_relative_path(path_str, manifest_dir):
            failures.append(f"vector {vector.get('id', '?')!r}: source.path {path_str!r} "
                            f"is not a safe relative path within the manifest tree")
    return failures


def check_vector_sources(manifest: dict, manifest_dir: Path) -> list[str]:
    import hashlib
    failures = []
    for vector in manifest.get("vectors", []):
        source = vector.get("source")
        vid = vector.get("id", "?")
        if source is None:
            continue
        if not isinstance(source, dict):
            continue

        # --- Recipe-only entries (no committed binary bytes) ---
        recipe = source.get("recipe")
        if recipe is not None:
            expected_len = source.get("expected_byte_length")
            expected_hash = source.get("expected_sha256")
            if not isinstance(recipe, str):
                failures.append(f"vector {vid!r}: source.recipe must be a string")
            if expected_len is not None and not isinstance(expected_len, int):
                failures.append(f"vector {vid!r}: source.expected_byte_length must be an integer")
            if expected_hash is not None and not isinstance(expected_hash, str):
                failures.append(f"vector {vid!r}: source.expected_sha256 must be a string")
            continue

        # --- Byte-range entries (offset + length within a binary source) ---
        path_str = source.get("path")
        offset = source.get("offset")
        length = source.get("length")
        declared_hash = source.get("sha256")

        if path_str is None:
            continue
        if not isinstance(path_str, str):
            continue
        if not _is_safe_relative_path(path_str, manifest_dir):
            continue  # already reported by check_vector_paths

        file_path = manifest_dir / path_str
        if not file_path.is_file():
            failures.append(f"vector {vid!r}: source file {path_str!r} not found")
            continue

        try:
            file_size = file_path.stat().st_size
        except OSError as exc:
            failures.append(f"vector {vid!r}: cannot stat source {path_str}: {exc}")
            continue

        # Validate offset and length are integers
        if not isinstance(offset, int) or offset < 0:
            failures.append(f"vector {vid!r}: source.offset must be a non-negative integer, "
                            f"got {offset!r}")
            continue
        if not isinstance(length, int) or length < 0:
            failures.append(f"vector {vid!r}: source.length must be a non-negative integer, "
                            f"got {length!r}")
            continue

        # Validate byte range is within file bounds
        if offset + length > file_size:
            failures.append(f"vector {vid!r}: byte range [{offset}, {offset + length}) "
                            f"exceeds source file size {file_size}")
            continue

        # Extract bytes and verify SHA-256
        try:
            with file_path.open("rb") as fh:
                fh.seek(offset)
                data = fh.read(length)
        except OSError as exc:
            failures.append(f"vector {vid!r}: cannot read source range "
                            f"{path_str}[{offset}:{offset + length}]: {exc}")
            continue

        if len(data) != length:
            failures.append(f"vector {vid!r}: read {len(data)} bytes but expected {length} "
                            f"from {path_str}[{offset}:{offset + length}]")
            continue

        if declared_hash is not None:
            actual_hash = hashlib.sha256(data).hexdigest()
            if actual_hash != declared_hash:
                failures.append(f"vector {vid!r}: sha256 mismatch — "
                                f"declared {declared_hash}, actual {actual_hash}")

        # Also check legacy byte_length if present (backward compat)
        declared_len = source.get("byte_length")
        if declared_len is not None and length != declared_len:
            failures.append(f"vector {vid!r}: byte_length {declared_len} != source.length "
                            f"{length}")

    return failures


def check_wav_sources_committed(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Reject null or missing sources for wav-validation vectors."""
    failures = []
    for vector in manifest.get("vectors", []):
        if vector.get("category") != "wav-validation":
            continue
        vid = vector.get("id", "?")
        source = vector.get("source")
        if source is None:
            failures.append(f"vector {vid!r}: wav-validation source must not be null")
            continue
        if not isinstance(source, dict):
            failures.append(f"vector {vid!r}: source must be a dict, got {type(source).__name__}")
            continue
        # Must have either a recipe or a path
        has_recipe = "recipe" in source
        has_path = "path" in source
        if not has_recipe and not has_path:
            failures.append(f"vector {vid!r}: source must have 'recipe' or 'path'")
    return failures


def check_recipe_integrity(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Independently verify every recipe-only vector against the known recipe.

    Validates that:
    - The recipe name matches the known canonical recipe.
    - The declared expected_sha256 and expected_byte_length match the
      independently stream-computed values.
    - The required fields (expected_sha256, expected_byte_length) are present.
    - The expectations data_bytes and total_bytes are consistent with the
      recipe.
    """
    failures = []
    for vector in manifest.get("vectors", []):
        source = vector.get("source")
        if not isinstance(source, dict):
            continue
        recipe = source.get("recipe")
        if recipe is None:
            continue
        vid = vector.get("id", "?")

        # Reject missing hash/length
        expected_hash = source.get("expected_sha256")
        expected_len = source.get("expected_byte_length")
        if expected_hash is None:
            failures.append(
                f"vector {vid!r}: recipe vector must declare source.expected_sha256"
            )
        if expected_len is None:
            failures.append(
                f"vector {vid!r}: recipe vector must declare source.expected_byte_length"
            )
        if expected_hash is None or expected_len is None:
            continue  # avoid cascading type-checks on missing fields

        if not isinstance(expected_hash, str) or not isinstance(expected_len, int):
            # already flagged by check_vector_sources
            continue

        # --- Recipe name must be exact ---
        if recipe != _MAX_RECIPE_NAME:
            failures.append(
                f"vector {vid!r}: unknown recipe {recipe!r}; "
                f"only {_MAX_RECIPE_NAME!r} is recognised"
            )
            continue

        # --- Length must match independently computed value ---
        if expected_len != _MAX_RECIPE_BYTE_LENGTH:
            failures.append(
                f"vector {vid!r}: expected_byte_length {expected_len} != "
                f"independently computed {_MAX_RECIPE_BYTE_LENGTH}"
            )

        # --- Hash must match independently stream-computed hash ---
        if expected_hash != _RECIPE_EXPECTED_HASH:
            failures.append(
                f"vector {vid!r}: expected_sha256 {expected_hash} != "
                f"independently computed {_RECIPE_EXPECTED_HASH}"
            )

        # --- Expectations must be consistent with the recipe ---
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            continue
        declared_data = exp.get("data_bytes")
        if declared_data is not None and declared_data != _MAX_RECIPE_DATA_BYTES:
            failures.append(
                f"vector {vid!r}: expectations.data_bytes {declared_data} != "
                f"recipe {_MAX_RECIPE_DATA_BYTES}"
            )
        declared_total = exp.get("total_bytes")
        if declared_total is not None and declared_total != _MAX_RECIPE_BYTE_LENGTH:
            failures.append(
                f"vector {vid!r}: expectations.total_bytes {declared_total} != "
                f"recipe {_MAX_RECIPE_BYTE_LENGTH}"
            )
        if exp.get("validation") != "pass":
            failures.append(
                f"vector {vid!r}: recipe vector expectations.validation must be 'pass'"
            )

    return failures


def check_pcm_sources_committed(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Reject null or missing sources for pcm-frames vectors."""
    failures = []
    for vector in manifest.get("vectors", []):
        if vector.get("category") != "pcm-frames":
            continue
        vid = vector.get("id", "?")
        source = vector.get("source")
        if source is None:
            failures.append(f"vector {vid!r}: pcm-frames source must not be null")
            continue
        if not isinstance(source, dict):
            failures.append(f"vector {vid!r}: source must be a dict, got {type(source).__name__}")
            continue
        has_path = "path" in source
        if not has_path:
            failures.append(f"vector {vid!r}: pcm-frames source must have 'path' (recipe-only not allowed for PCM)")
    return failures


def check_pcm_expectations(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Validate PCM vector expectations: required fields, consistent counts, non-contradictory events/timestamps."""
    failures = []
    for vector in manifest.get("vectors", []):
        if vector.get("category") != "pcm-frames":
            continue
        vid = vector.get("id", "?")
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            failures.append(f"vector {vid!r}: expectations must be a dict")
            continue

        # Required fields
        for field in ("frame_count", "total_bytes", "odd_byte_pending", "terminal_failure"):
            if field not in exp:
                failures.append(f"vector {vid!r}: expectations missing required field {field!r}")

        frame_count = exp.get("frame_count")
        total_bytes = exp.get("total_bytes")
        odd_byte_pending = exp.get("odd_byte_pending")
        terminal_failure = exp.get("terminal_failure")

        # Type checks
        if not isinstance(frame_count, int) or frame_count < 0:
            failures.append(f"vector {vid!r}: frame_count must be a non-negative integer, got {frame_count!r}")
        if not isinstance(total_bytes, int) or total_bytes < 0:
            failures.append(f"vector {vid!r}: total_bytes must be a non-negative integer, got {total_bytes!r}")
        if not isinstance(odd_byte_pending, bool):
            failures.append(f"vector {vid!r}: odd_byte_pending must be a boolean, got {odd_byte_pending!r}")
        if not isinstance(terminal_failure, bool):
            failures.append(f"vector {vid!r}: terminal_failure must be a boolean, got {terminal_failure!r}")

        # Consistency: terminal_failure requires failure_reason
        if terminal_failure and "failure_reason" not in exp:
            failures.append(f"vector {vid!r}: terminal_failure=true requires failure_reason")
        if not terminal_failure and "failure_reason" in exp:
            failures.append(f"vector {vid!r}: failure_reason present but terminal_failure=false")

        # Consistency: frame_count * 2 <= total_bytes (minimum 2 bytes per frame)
        if isinstance(frame_count, int) and isinstance(total_bytes, int) and frame_count >= 0:
            if frame_count * 2 > total_bytes:
                failures.append(f"vector {vid!r}: frame_count={frame_count} requires at least "
                                f"{frame_count * 2} bytes but total_bytes={total_bytes}")

        # Consistency: odd_byte_pending means a byte not yet consumed into a frame,
        # so total_bytes can be even (the pending byte is extra).
        # No contradiction check needed between odd_byte_pending and total_bytes parity.

        # Events validation
        events = exp.get("events")
        if events is not None:
            if not isinstance(events, list):
                failures.append(f"vector {vid!r}: events must be a list, got {type(events).__name__}")
            else:
                valid_events = {"End", "Discontinuity", "DeviceFailure", "Restart"}
                for evt in events:
                    if not isinstance(evt, str):
                        failures.append(f"vector {vid!r}: event must be a string, got {type(evt).__name__}")
                    elif evt not in valid_events:
                        failures.append(f"vector {vid!r}: unknown event {evt!r}; valid: {sorted(valid_events)}")

                # DeviceFailure must be the last event if present
                if "DeviceFailure" in events and events[-1] != "DeviceFailure":
                    failures.append(f"vector {vid!r}: DeviceFailure must be the last event")

                # terminal_failure must have DeviceFailure event
                if terminal_failure and "DeviceFailure" not in events:
                    failures.append(f"vector {vid!r}: terminal_failure=true requires DeviceFailure event")
                if not terminal_failure and "DeviceFailure" in events:
                    failures.append(f"vector {vid!r}: DeviceFailure event present but terminal_failure=false")

                # End must be the last event unless DeviceFailure is last
                if "End" in events:
                    end_idx = len(events) - 1 - events[::-1].index("End")
                    last_idx = len(events) - 1
                    if end_idx != last_idx and events[last_idx] != "DeviceFailure":
                        failures.append(f"vector {vid!r}: End must be the last event (or second-to-last before DeviceFailure)")

        # Sequence/timestamp consistency
        first_seq = exp.get("first_sequence")
        last_seq = exp.get("last_sequence")
        if first_seq is not None and last_seq is not None:
            if not isinstance(first_seq, int) or first_seq < 0:
                failures.append(f"vector {vid!r}: first_sequence must be a non-negative integer")
            if not isinstance(last_seq, int) or last_seq < 0:
                failures.append(f"vector {vid!r}: last_sequence must be a non-negative integer")
            if isinstance(first_seq, int) and isinstance(last_seq, int) and isinstance(frame_count, int):
                expected_last = first_seq + frame_count - 1
                if frame_count > 0 and last_seq != expected_last:
                    failures.append(f"vector {vid!r}: last_sequence={last_seq} but expected "
                                    f"{expected_last} (first={first_seq}, count={frame_count})")

        first_ts = exp.get("first_timestamp_ns")
        last_ts = exp.get("last_timestamp_ns")
        if first_ts is not None and last_ts is not None:
            if not isinstance(first_ts, int) or first_ts < 0:
                failures.append(f"vector {vid!r}: first_timestamp_ns must be a non-negative integer")
            if not isinstance(last_ts, int) or last_ts < 0:
                failures.append(f"vector {vid!r}: last_timestamp_ns must be a non-negative integer")
            # timestamp_ns = completed_samples * 62500
            # After N frames: N * 320 * 62500 = N * 20000000
            if isinstance(first_ts, int) and isinstance(last_ts, int) and first_ts > last_ts:
                failures.append(f"vector {vid!r}: first_timestamp_ns ({first_ts}) > last_timestamp_ns ({last_ts})")

            # timestampNs must equal completed_samples * 62500 for the last frame.
            # completed_samples = total_bytes // 2 (each sample is 2 bytes).
            # Skip terminal and restart vectors (they have alternative timestamp semantics).
            if isinstance(first_ts, int) and isinstance(last_ts, int) and isinstance(total_bytes, int):
                epochs = exp.get("epochs")
                if not terminal_failure and epochs is None:
                    completed_samples = total_bytes // 2
                    expected_last_ts = completed_samples * 62500
                    if last_ts != expected_last_ts:
                        failures.append(f"vector {vid!r}: last_timestamp_ns={last_ts} but "
                                        f"completed_samples={completed_samples} (total_bytes={total_bytes}) "
                                        f"gives expected {expected_last_ts}")

                    # For multi-frame vectors, first frame =320 samples =20M ns.
                    # For single-frame, first_ts = last_ts.
                    if isinstance(frame_count, int) and frame_count > 1:
                        expected_first_ts = 320 * 62500  # 20,000,000
                        if first_ts != expected_first_ts:
                            failures.append(f"vector {vid!r}: first_timestamp_ns={first_ts} but "
                                            f"expected {expected_first_ts} for a multi-frame epoch")

        # Epochs validation (for restart vectors)
        epochs = exp.get("epochs")
        if epochs is not None:
            if not isinstance(epochs, list):
                failures.append(f"vector {vid!r}: epochs must be a list")
            else:
                for ei, epoch in enumerate(epochs):
                    if not isinstance(epoch, dict):
                        failures.append(f"vector {vid!r}: epoch[{ei}] must be a dict")
                        continue
                    seq_range = epoch.get("sequence_range")
                    ts_range = epoch.get("timestamp_ns_range")
                    if seq_range is not None:
                        if not isinstance(seq_range, list) or len(seq_range) != 2:
                            failures.append(f"vector {vid!r}: epoch[{ei}].sequence_range must be [start, end]")
                    if ts_range is not None:
                        if not isinstance(ts_range, list) or len(ts_range) != 2:
                            failures.append(f"vector {vid!r}: epoch[{ei}].timestamp_ns_range must be [start, end]")

        # Callback plan validation (for fragmented vectors)
        callback_plan = exp.get("callback_plan")
        if callback_plan is not None:
            if not isinstance(callback_plan, list):
                failures.append(f"vector {vid!r}: callback_plan must be a list")
            else:
                plan_bytes = 0
                plan_frames = 0
                for ci, cb in enumerate(callback_plan):
                    if not isinstance(cb, dict):
                        failures.append(f"vector {vid!r}: callback_plan[{ci}] must be a dict")
                        continue
                    db = cb.get("deliver_bytes")
                    fe = cb.get("frames_emitted")
                    if isinstance(db, int):
                        plan_bytes += db
                    if isinstance(fe, int):
                        plan_frames += fe
                if isinstance(total_bytes, int) and plan_bytes != total_bytes:
                    failures.append(f"vector {vid!r}: callback_plan delivers {plan_bytes} bytes "
                                    f"but total_bytes={total_bytes}")
                if isinstance(frame_count, int) and plan_frames != frame_count:
                    failures.append(f"vector {vid!r}: callback_plan emits {plan_frames} frames "
                                    f"but frame_count={frame_count}")

    return failures


def check_no_platform_overrides(manifest: dict, _manifest_dir: Path) -> list[str]:
    failures = []
    for vector in manifest.get("vectors", []):
        vid = vector.get("id", "?")
        for key in vector:
            if any(sub in key.lower() for sub in FORBIDDEN_PLATFORM_SUBSTRINGS):
                failures.append(f"vector {vid!r}: forbidden platform-local key {key!r}")
        source = vector.get("source")
        if isinstance(source, dict):
            for key in source:
                if any(sub in key.lower() for sub in FORBIDDEN_PLATFORM_SUBSTRINGS):
                    failures.append(f"vector {vid!r} source: forbidden platform-local key {key!r}")
    return failures


def check_seek_expectations(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Validate seek vector expectations: required fields, arithmetic consistency, clamping."""
    failures = []
    for vector in manifest.get("vectors", []):
        if vector.get("category") != "seek":
            continue
        vid = vector.get("id", "?")
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            failures.append(f"vector {vid!r}: expectations must be a dict")
            continue

        # All seek vectors now use the unified metadata-rich shape.
        for field in ("duration_ms", "data_bytes", "data_start",
                      "requested_ms", "effective_ns", "aligned_byte", "state"):
            if field not in exp:
                failures.append(f"vector {vid!r}: seek expectations missing required field {field!r}")

        duration_ms = exp.get("duration_ms")
        data_bytes = exp.get("data_bytes")
        data_start = exp.get("data_start")
        requested_ms = exp.get("requested_ms")
        effective_ns = exp.get("effective_ns")
        aligned_byte = exp.get("aligned_byte")
        state = exp.get("state")

        # Type checks
        for field, val in (("duration_ms", duration_ms), ("data_bytes", data_bytes),
                           ("data_start", data_start), ("requested_ms", requested_ms),
                           ("effective_ns", effective_ns), ("aligned_byte", aligned_byte)):
            if val is not None and not isinstance(val, int):
                failures.append(f"vector {vid!r}: {field} must be an integer, got {type(val).__name__}")

        if state is not None and state not in ("ready", "completed"):
            failures.append(f"vector {vid!r}: state must be 'ready' or 'completed', got {state!r}")

        # Skip arithmetic checks if any required field is missing or wrong type
        required_ints = (duration_ms, data_bytes, data_start, requested_ms, effective_ns, aligned_byte)
        if any(v is None or not isinstance(v, int) for v in required_ints):
            continue

        # --- Clamping checks ---
        if requested_ms < 0:
            if effective_ns != 0:
                failures.append(f"vector {vid!r}: negative requested_ms={requested_ms} "
                                f"must clamp to effective_ns=0, got {effective_ns}")
        elif requested_ms > duration_ms:
            expected_clamped_ns = duration_ms * 1000000
            if effective_ns != expected_clamped_ns:
                failures.append(f"vector {vid!r}: requested_ms={requested_ms} > duration_ms={duration_ms} "
                                f"must clamp to effective_ns={expected_clamped_ns}, got {effective_ns}")
        elif requested_ms == duration_ms:
            if state != "completed":
                failures.append(f"vector {vid!r}: requested_ms equals duration_ms, state must be 'completed'")

        # --- Byte range checks ---
        data_end = data_start + data_bytes
        if aligned_byte < data_start:
            failures.append(f"vector {vid!r}: aligned_byte={aligned_byte} < data_start={data_start}")
        if aligned_byte > data_end:
            failures.append(f"vector {vid!r}: aligned_byte={aligned_byte} > data_end={data_end}")

        # --- Independent seek formula computation ---
        # Compute expected values from validation-contract.md Seek procedure:
        #   step 1: clamp requested_ms to [0, duration_ms]
        #   step 2: samples = clamped_ms * 16000 // 1000
        #   step 3-4: aligned_byte = data_start + (samples * 2) aligned downward to even
        #   step 7: effective_ns = samples * 62500
        #   seek-to-end: completed state, aligned_byte = data_end,
        #                effective_ns = duration_ms * 1_000_000
        clamped_ms = max(0, min(requested_ms, duration_ms))

        if requested_ms >= duration_ms:
            # clamped to end → completed
            expected_state = "completed"
            expected_aligned = data_end
            # 1 ms = 1_000_000 ns (not 100_000_000 as the previous contract typo claimed)
            expected_ns = duration_ms * 1_000_000
        else:
            expected_state = "ready"
            samples = (clamped_ms * 16000) // 1000
            byte_offset = samples * 2
            # Step 4: align downward to nearest even boundary
            if byte_offset % 2 != 0:
                byte_offset -= 1
            expected_aligned = data_start + byte_offset
            expected_ns = samples * 62500

        if state != expected_state:
            failures.append(f"vector {vid!r}: state={state!r} but independently computed "
                            f"expected {expected_state!r} "
                            f"(clamped_ms={clamped_ms}, duration_ms={duration_ms})")
        if aligned_byte != expected_aligned:
            failures.append(f"vector {vid!r}: aligned_byte={aligned_byte} but independently "
                            f"computed expected {expected_aligned} "
                            f"(clamped_ms={clamped_ms}, samples={(clamped_ms*16000)//1000}, "
                            f"byte_offset={expected_aligned - data_start})")
        if effective_ns != expected_ns:
            failures.append(f"vector {vid!r}: effective_ns={effective_ns} but independently "
                            f"computed expected {expected_ns} "
                            f"(clamped_ms={clamped_ms})")

        # --- State-specific checks ---
        if state == "completed":
            if aligned_byte != data_end:
                failures.append(f"vector {vid!r}: completed state requires aligned_byte={data_end}, "
                                f"got {aligned_byte}")
            expected_end_ns = duration_ms * 1000000
            if effective_ns != expected_end_ns:
                failures.append(f"vector {vid!r}: completed state requires effective_ns={expected_end_ns}, "
                                f"got {effective_ns}")
        elif state == "ready":
            if aligned_byte >= data_end:
                failures.append(f"vector {vid!r}: ready state requires aligned_byte < data_end, "
                                f"got {aligned_byte}")
            # Even-byte alignment: aligned_byte must be even (samples are 2 bytes)
            if (aligned_byte - data_start) % 2 != 0:
                failures.append(f"vector {vid!r}: aligned_byte={aligned_byte} not even-aligned "
                                f"(offset from data_start={data_start} is {aligned_byte - data_start})")
            # effective_ns must match the sample arithmetic
            sample_offset = (aligned_byte - data_start) // 2
            expected_ns = sample_offset * 62500
            if effective_ns != expected_ns:
                failures.append(f"vector {vid!r}: effective_ns={effective_ns} but "
                                f"aligned_byte={aligned_byte} implies {expected_ns} "
                                f"(sample_offset={sample_offset} * 62500)")

    return failures


def check_marker_expectations(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Validate marker vector expectations: required fields, type consistency, valid classifications."""
    failures = []
    VALID_CLASSIFICATIONS = frozenset({"candidate", "ordinary_attachment", "invalid_marked_content"})
    VALID_STORES = frozenset({"Normal", "Fast"})

    for vector in manifest.get("vectors", []):
        if vector.get("category") != "marker":
            continue
        vid = vector.get("id", "?")
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            failures.append(f"vector {vid!r}: expectations must be a dict")
            continue

        # Required fields
        for field in ("filename", "marker_parses", "classification"):
            if field not in exp:
                failures.append(f"vector {vid!r}: marker expectations missing required field {field!r}")

        filename = exp.get("filename")
        marker_parses = exp.get("marker_parses")
        classification = exp.get("classification")
        extracted_id = exp.get("extracted_message_id")
        id_matches = exp.get("message_id_matches")
        store = exp.get("store")
        store_valid = exp.get("store_valid")
        attachment_id = exp.get("attachment_message_id")

        # Type checks
        if filename is not None and not isinstance(filename, str):
            failures.append(f"vector {vid!r}: filename must be a string, got {type(filename).__name__}")
        if marker_parses is not None and not isinstance(marker_parses, bool):
            failures.append(f"vector {vid!r}: marker_parses must be a boolean, got {type(marker_parses).__name__}")
        if classification is not None and classification not in VALID_CLASSIFICATIONS:
            failures.append(f"vector {vid!r}: classification must be one of {sorted(VALID_CLASSIFICATIONS)}, "
                            f"got {classification!r}")
        if store is not None and store not in VALID_STORES:
            failures.append(f"vector {vid!r}: store must be one of {sorted(VALID_STORES)}, got {store!r}")
        if store_valid is not None and not isinstance(store_valid, bool):
            failures.append(f"vector {vid!r}: store_valid must be a boolean, got {type(store_valid).__name__}")
        if id_matches is not None and not isinstance(id_matches, bool):
            failures.append(f"vector {vid!r}: message_id_matches must be a boolean, got {type(id_matches).__name__}")

        # Consistency: if marker_parses is true, extracted_message_id must be present
        if marker_parses is True:
            if extracted_id is None:
                failures.append(f"vector {vid!r}: marker_parses=true requires extracted_message_id")
            elif not isinstance(extracted_id, str):
                failures.append(f"vector {vid!r}: extracted_message_id must be a string, "
                                f"got {type(extracted_id).__name__}")

        # Consistency: if marker_parses is false, extracted_message_id should not be present
        if marker_parses is False and extracted_id is not None:
            failures.append(f"vector {vid!r}: marker_parses=false but extracted_message_id is present")

        # Consistency: message_id_matches requires both extracted_message_id and attachment_message_id
        if id_matches is True:
            if extracted_id is None or attachment_id is None:
                failures.append(f"vector {vid!r}: message_id_matches=true requires both "
                                f"extracted_message_id and attachment_message_id")
            elif extracted_id != attachment_id:
                failures.append(f"vector {vid!r}: message_id_matches=true but "
                                f"extracted_message_id={extracted_id!r} != attachment_message_id={attachment_id!r}")
        if id_matches is False:
            if extracted_id is None or attachment_id is None:
                failures.append(f"vector {vid!r}: message_id_matches=false requires both "
                                f"extracted_message_id and attachment_message_id")
            elif extracted_id == attachment_id:
                failures.append(f"vector {vid!r}: message_id_matches=false but "
                                f"extracted_message_id={extracted_id!r} == attachment_message_id={attachment_id!r}")

        # Consistency: store_valid=false requires store to be present and not "Normal"
        if store_valid is False:
            if store is None:
                failures.append(f"vector {vid!r}: store_valid=false requires store field")
            elif store == "Normal":
                failures.append(f"vector {vid!r}: store_valid=false but store is Normal")

    return failures


def check_receiver_expectations(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Validate receiver vector expectations: required fields, allowed states, valid transitions."""
    failures = []
    VALID_RECEIVER_STATES = frozenset({
        "Candidate", "Fetching", "Playable", "Invalid marked content", "Unavailable"
    })

    for vector in manifest.get("vectors", []):
        if vector.get("category") != "receiver":
            continue
        vid = vector.get("id", "?")
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            failures.append(f"vector {vid!r}: expectations must be a dict")
            continue

        # Required fields
        for field in ("initial_state", "event", "resulting_state",
                      "refetch_allowed", "save_export_available", "fallback_to_ordinary"):
            if field not in exp:
                failures.append(f"vector {vid!r}: receiver expectations missing required field {field!r}")

        initial_state = exp.get("initial_state")
        event = exp.get("event")
        resulting_state = exp.get("resulting_state")
        refetch_allowed = exp.get("refetch_allowed")
        save_export_available = exp.get("save_export_available")
        fallback_to_ordinary = exp.get("fallback_to_ordinary")
        internal_only = exp.get("internal_only")

        # Type checks
        if initial_state is not None and initial_state not in VALID_RECEIVER_STATES:
            failures.append(f"vector {vid!r}: initial_state must be one of {sorted(VALID_RECEIVER_STATES)}, "
                            f"got {initial_state!r}")
        if event is not None and not isinstance(event, str):
            failures.append(f"vector {vid!r}: event must be a string, got {type(event).__name__}")
        if resulting_state is not None and resulting_state not in VALID_RECEIVER_STATES:
            failures.append(f"vector {vid!r}: resulting_state must be one of {sorted(VALID_RECEIVER_STATES)}, "
                            f"got {resulting_state!r}")
        if refetch_allowed is not None and not isinstance(refetch_allowed, bool):
            failures.append(f"vector {vid!r}: refetch_allowed must be a boolean, got {type(refetch_allowed).__name__}")
        if save_export_available is not None and not isinstance(save_export_available, bool):
            failures.append(f"vector {vid!r}: save_export_available must be a boolean, got {type(save_export_available).__name__}")
        if fallback_to_ordinary is not None and not isinstance(fallback_to_ordinary, bool):
            failures.append(f"vector {vid!r}: fallback_to_ordinary must be a boolean, got {type(fallback_to_ordinary).__name__}")
        if internal_only is not None and not isinstance(internal_only, bool):
            failures.append(f"vector {vid!r}: internal_only must be a boolean, got {type(internal_only).__name__}")

        # Consistency: invalid-fallback must not refetch
        if resulting_state == "Invalid marked content" and refetch_allowed is True:
            failures.append(f"vector {vid!r}: Invalid marked content must have refetch_allowed=false")

        # Consistency: save/export only available in Playable state
        if save_export_available is True and resulting_state != "Playable":
            failures.append(f"vector {vid!r}: save_export_available=true requires resulting_state=Playable, "
                            f"got {resulting_state!r}")

        # Consistency: Playable state must have save_export_available
        if resulting_state == "Playable" and save_export_available is False:
            failures.append(f"vector {vid!r}: Playable resulting_state requires save_export_available=true")

        # Consistency: fallback_to_ordinary must match Invalid marked content
        if fallback_to_ordinary is True and resulting_state != "Invalid marked content":
            failures.append(f"vector {vid!r}: fallback_to_ordinary=true requires resulting_state=Invalid marked content, "
                            f"got {resulting_state!r}")
        if fallback_to_ordinary is False and resulting_state == "Invalid marked content":
            failures.append(f"vector {vid!r}: resulting_state=Invalid marked content requires fallback_to_ordinary=true")

        # Consistency: Unavailable state must have refetch_allowed=false
        if resulting_state == "Unavailable" and refetch_allowed is True:
            failures.append(f"vector {vid!r}: Unavailable state must have refetch_allowed=false")

    return failures


def check_registry_expectations(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Validate registry vector expectations: required fields for cap, stale, owner-invalid, and crash-boundary vectors."""
    failures = []
    VALID_SUB_CATEGORIES = frozenset({"cap", "stale", "owner-invalid", "crash-boundary"})
    VALID_RECOVERY_OUTCOMES = frozenset({
        "recoverable_draft", "durable_queued_message", "diagnosed_invalid"
    })

    for vector in manifest.get("vectors", []):
        if vector.get("category") != "registry":
            continue
        vid = vector.get("id", "?")
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            failures.append(f"vector {vid!r}: expectations must be a dict")
            continue

        sub_cat = exp.get("sub_category")
        if sub_cat is None:
            failures.append(f"vector {vid!r}: registry expectations missing required field 'sub_category'")
            continue
        if sub_cat not in VALID_SUB_CATEGORIES:
            failures.append(f"vector {vid!r}: sub_category must be one of {sorted(VALID_SUB_CATEGORIES)}, "
                            f"got {sub_cat!r}")
            continue

        recovery_outcome = exp.get("recovery_outcome")
        if recovery_outcome is not None and recovery_outcome not in VALID_RECOVERY_OUTCOMES:
            failures.append(f"vector {vid!r}: recovery_outcome must be one of {sorted(VALID_RECOVERY_OUTCOMES)}, "
                            f"got {recovery_outcome!r}")

        if sub_cat == "cap":
            _validate_registry_cap(vector, exp, failures)
        elif sub_cat == "stale":
            _validate_registry_stale(vector, exp, failures)
        elif sub_cat == "owner-invalid":
            _validate_registry_owner_invalid(vector, exp, failures)
        elif sub_cat == "crash-boundary":
            _validate_registry_crash_boundary(vector, exp, failures)

    return failures


def _validate_registry_cap(vector: dict, exp: dict, failures: list[str]):
    """Validate cap subcategory registry vectors."""
    vid = vector.get("id", "?")
    for field in ("registry_entry_count", "blocked_creation", "existing_drafts_available"):
        if field not in exp:
            failures.append(f"vector {vid!r}: registry cap missing required field {field!r}")

    entry_count = exp.get("registry_entry_count")
    blocked = exp.get("blocked_creation")
    existing = exp.get("existing_drafts_available")

    if entry_count is not None and not isinstance(entry_count, int):
        failures.append(f"vector {vid!r}: registry_entry_count must be an integer, got {type(entry_count).__name__}")
    if entry_count is not None and isinstance(entry_count, int) and entry_count < 0:
        failures.append(f"vector {vid!r}: registry_entry_count must be non-negative, got {entry_count}")
    if blocked is not None and not isinstance(blocked, bool):
        failures.append(f"vector {vid!r}: blocked_creation must be a boolean, got {type(blocked).__name__}")
    if existing is not None and not isinstance(existing, bool):
        failures.append(f"vector {vid!r}: existing_drafts_available must be a boolean, got {type(existing).__name__}")

    # Consistency: at cap (10) means blocked creation
    if isinstance(entry_count, int) and entry_count >= 10 and blocked is False:
        failures.append(f"vector {vid!r}: registry_entry_count={entry_count} >= 10 requires blocked_creation=true")
    if isinstance(entry_count, int) and entry_count < 10 and blocked is True:
        failures.append(f"vector {vid!r}: registry_entry_count={entry_count} < 10 cannot have blocked_creation=true")


def _validate_registry_stale(vector: dict, exp: dict, failures: list[str]):
    """Validate stale subcategory registry vectors."""
    vid = vector.get("id", "?")
    for field in ("draft_age_days", "stale_review_indicated", "silently_deleted"):
        if field not in exp:
            failures.append(f"vector {vid!r}: registry stale missing required field {field!r}")

    age = exp.get("draft_age_days")
    stale_indicated = exp.get("stale_review_indicated")
    silently_deleted = exp.get("silently_deleted")

    if age is not None and not isinstance(age, int):
        failures.append(f"vector {vid!r}: draft_age_days must be an integer, got {type(age).__name__}")
    if age is not None and isinstance(age, int) and age < 0:
        failures.append(f"vector {vid!r}: draft_age_days must be non-negative, got {age}")
    if stale_indicated is not None and not isinstance(stale_indicated, bool):
        failures.append(f"vector {vid!r}: stale_review_indicated must be a boolean, got {type(stale_indicated).__name__}")
    if silently_deleted is not None and not isinstance(silently_deleted, bool):
        failures.append(f"vector {vid!r}: silently_deleted must be a boolean, got {type(silently_deleted).__name__}")

    # Consistency: age >=30 triggers stale review
    if isinstance(age, int) and age >= 30 and stale_indicated is False:
        failures.append(f"vector {vid!r}: draft_age_days={age} >= 30 requires stale_review_indicated=true")
    # Consistency: stale review never silently deletes
    if stale_indicated is True and silently_deleted is True:
        failures.append(f"vector {vid!r}: stale_review_indicated=true requires silently_deleted=false")


def _validate_registry_owner_invalid(vector: dict, exp: dict, failures: list[str]):
    """Validate owner-invalid subcategory registry vectors."""
    vid = vector.get("id", "?")
    for field in ("owner_can_send", "preview_delete_only", "retargeted"):
        if field not in exp:
            failures.append(f"vector {vid!r}: registry owner-invalid missing required field {field!r}")

    owner_can_send = exp.get("owner_can_send")
    preview_only = exp.get("preview_delete_only")
    retargeted = exp.get("retargeted")

    if owner_can_send is not None and not isinstance(owner_can_send, bool):
        failures.append(f"vector {vid!r}: owner_can_send must be a boolean, got {type(owner_can_send).__name__}")
    if preview_only is not None and not isinstance(preview_only, bool):
        failures.append(f"vector {vid!r}: preview_delete_only must be a boolean, got {type(preview_only).__name__}")
    if retargeted is not None and not isinstance(retargeted, bool):
        failures.append(f"vector {vid!r}: retargeted must be a boolean, got {type(retargeted).__name__}")

    # Consistency: owner cannot send => Preview/Delete-only, never retargeted
    if owner_can_send is False:
        if preview_only is not True:
            failures.append(f"vector {vid!r}: owner_can_send=false requires preview_delete_only=true")
        if retargeted is not False:
            failures.append(f"vector {vid!r}: owner_can_send=false requires retargeted=false")
        if exp.get("recovery_outcome") != "diagnosed_invalid":
            failures.append(f"vector {vid!r}: owner_can_send=false requires recovery_outcome=diagnosed_invalid")
    # Consistency: owner can send => not Preview/Delete-only
    if owner_can_send is True and preview_only is True:
        failures.append(f"vector {vid!r}: owner_can_send=true cannot have preview_delete_only=true")


def _validate_registry_crash_boundary(vector: dict, exp: dict, failures: list[str]):
    """Validate crash-boundary subcategory registry vectors."""
    vid = vector.get("id", "?")
    for field in ("step", "crash_point", "initial_event", "registry_entry_exists",
                  "wav_file_exists", "message_queued", "message_id_allocated",
                  "encrypted_blob_exists", "recovery_outcome",
                  "duplicate_send_possible", "plaintext_dependency"):
        if field not in exp:
            failures.append(f"vector {vid!r}: registry crash-boundary missing required field {field!r}")

    step = exp.get("step")
    registry_entry = exp.get("registry_entry_exists")
    wav_file = exp.get("wav_file_exists")
    wav_valid = exp.get("wav_file_valid")
    message_queued = exp.get("message_queued")
    msg_id_alloc = exp.get("message_id_allocated")
    encrypted_blob = exp.get("encrypted_blob_exists")
    recovery = exp.get("recovery_outcome")
    dup_send = exp.get("duplicate_send_possible")
    plaintext_dep = exp.get("plaintext_dependency")
    crash_point = exp.get("crash_point")
    initial_event = exp.get("initial_event")

    # Type checks
    if step is not None and (not isinstance(step, int) or step < 1 or step > 10):
        failures.append(f"vector {vid!r}: step must be an integer 1..10, got {step!r}")
    if crash_point is not None and not isinstance(crash_point, str):
        failures.append(f"vector {vid!r}: crash_point must be a string, got {type(crash_point).__name__}")
    if initial_event is not None and not isinstance(initial_event, str):
        failures.append(f"vector {vid!r}: initial_event must be a string, got {type(initial_event).__name__}")

    for field_name, v in (
        ("registry_entry_exists", registry_entry),
        ("message_queued", message_queued),
        ("message_id_allocated", msg_id_alloc),
        ("encrypted_blob_exists", encrypted_blob),
        ("wav_file_exists", wav_file),
        ("duplicate_send_possible", dup_send),
        ("plaintext_dependency", plaintext_dep),
    ):
        if v is not None and not isinstance(v, bool):
            failures.append(f"vector {vid!r}: {field_name} must be a boolean, got {type(v).__name__}")

    # wav_file_valid can be boolean or null
    if wav_valid is not None and not isinstance(wav_valid, bool):
        failures.append(f"vector {vid!r}: wav_file_valid must be a boolean or null, got {type(wav_valid).__name__}")

    # Consistency: duplicate send must never be possible
    if dup_send is True:
        failures.append(f"vector {vid!r}: duplicate_send_possible must always be false "
                        f"(anti-duplicate-send guarantee)")

    # Consistency: plaintext_dependency must always be false
    if plaintext_dep is True:
        failures.append(f"vector {vid!r}: plaintext_dependency must always be false "
                        f"(no queued message dependent on deleted plaintext)")

    # Consistency: every crash boundary maps to exactly one allowed recovery outcome
    VALID_RECOVERY_OUTCOMES = frozenset(
        {"recoverable_draft", "durable_queued_message", "diagnosed_invalid"})
    if recovery is not None and recovery not in VALID_RECOVERY_OUTCOMES:
        failures.append(f"vector {vid!r}: recovery_outcome must be one of "
                        f"{sorted(VALID_RECOVERY_OUTCOMES)}, got {recovery!r}")

    # Consistency: when wav_file_exists is false, wav_file_valid must be null
    if wav_file is False and wav_valid is not None:
        failures.append(f"vector {vid!r}: wav_file_exists=false requires wav_file_valid=null, "
                        f"got {wav_valid!r}")

    # Consistency: step progression invariants
    # Steps 1-6: no message_id, no encrypted blob, no queued message
    if isinstance(step, int) and step <= 6:
        if msg_id_alloc is True:
            failures.append(f"vector {vid!r}: step {step} cannot have message_id_allocated=true "
                            f"(ID allocated at step 7)")
        if encrypted_blob is True:
            failures.append(f"vector {vid!r}: step {step} cannot have encrypted_blob_exists=true "
                            f"(blob created at step 8)")
        if message_queued is True:
            failures.append(f"vector {vid!r}: step {step} cannot have message_queued=true "
                            f"(message queued at step 9)")

    # Step 7: message_id allocated, no encrypted blob, no queued message
    if isinstance(step, int) and step == 7 and msg_id_alloc is False:
        failures.append(f"vector {vid!r}: step 7 requires message_id_allocated=true")

    # Steps 8-10: message_id allocated
    if isinstance(step, int) and step >= 8:
        if msg_id_alloc is False:
            failures.append(f"vector {vid!r}: step {step} requires message_id_allocated=true "
                            f"(ID allocated at step 7)")

    # Step 9-10: encrypted blob exists, message queued
    if isinstance(step, int) and step >= 9:
        if encrypted_blob is False:
            failures.append(f"vector {vid!r}: step {step} requires encrypted_blob_exists=true "
                            f"(blob created at step 8)")
        if message_queued is False:
            failures.append(f"vector {vid!r}: step {step} requires message_queued=true "
                            f"(message queued at step 9)")

    # Step 10: registry entry removed, plaintext deleted
    if isinstance(step, int) and step == 10:
        if registry_entry is True:
            failures.append(f"vector {vid!r}: step 10 requires registry_entry_exists=false")
        if wav_file is True:
            failures.append(f"vector {vid!r}: step 10 requires wav_file_exists=false")


def check_registry_crash_step_coverage(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Require all ten crash-step vectors (steps 1-10) to be present."""
    failures = []
    crash_steps_found: set[int] = set()
    for vector in manifest.get("vectors", []):
        if vector.get("category") != "registry":
            continue
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            continue
        if exp.get("sub_category") != "crash-boundary":
            continue
        step = exp.get("step")
        if isinstance(step, int) and 1 <= step <= 10:
            crash_steps_found.add(step)

    for step in range(1, 11):
        if step not in crash_steps_found:
            failures.append(f"registry crash-boundary step {step} missing; "
                            f"all ten steps 1-10 required")
    return failures


def check_scheduler_expectations(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Validate scheduler vector expectations: required fields, rule coverage, trace consistency."""
    failures = []
    rules_found: set[int] = set()

    for vector in manifest.get("vectors", []):
        if vector.get("category") != "scheduler":
            continue
        vid = vector.get("id", "?")
        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            failures.append(f"vector {vid!r}: expectations must be a dict")
            continue

        rule = exp.get("rule")
        if rule is None:
            failures.append(f"vector {vid!r}: scheduler expectations missing required field 'rule'")
        elif not isinstance(rule, int) or rule < 1 or rule > 9:
            failures.append(f"vector {vid!r}: rule must be an integer 1..9, got {rule!r}")
        else:
            rules_found.add(rule)

        inp = exp.get("input")
        expected = exp.get("expected")
        if not isinstance(inp, dict):
            failures.append(f"vector {vid!r}: scheduler expectations missing required 'input' dict")
        if not isinstance(expected, dict):
            failures.append(f"vector {vid!r}: scheduler expectations missing required 'expected' dict")

        if not isinstance(inp, dict) or not isinstance(expected, dict):
            continue

        # Rule-specific input/expected field validation
        if rule == 1:
            if "user_initiated" not in inp:
                failures.append(f"vector {vid!r}: rule 1 input missing 'user_initiated'")
            if "admitted" not in expected or "priority" not in expected:
                failures.append(f"vector {vid!r}: rule 1 expected missing 'admitted' or 'priority'")
            if expected.get("priority") != "user-initiated":
                failures.append(f"vector {vid!r}: rule 1 expected.priority must be 'user-initiated'")
        elif rule == 2:
            if expected.get("priority") != "voice":
                failures.append(f"vector {vid!r}: rule 2 expected.priority must be 'voice'")
        elif rule == 3:
            if expected.get("priority") != "image":
                failures.append(f"vector {vid!r}: rule 3 expected.priority must be 'image'")
        elif rule == 4:
            if "consecutive_voice_admissions" not in inp:
                failures.append(f"vector {vid!r}: rule 4 input missing 'consecutive_voice_admissions'")
            if inp.get("consecutive_voice_admissions") != 3:
                failures.append(f"vector {vid!r}: rule 4 input.consecutive_voice_admissions must be 3")
            if expected.get("voice_counter_reset") is not True:
                failures.append(f"vector {vid!r}: rule 4 expected.voice_counter_reset must be true")
            if expected.get("priority") != "image":
                failures.append(f"vector {vid!r}: rule 4 expected.priority must be 'image' "
                                f"(3:1 fairness forces image after three voice admissions)")
        elif rule == 5:
            if expected.get("interrupted") is not False or expected.get("cancelled") is not False:
                failures.append(f"vector {vid!r}: rule 5 expected must have interrupted=false, cancelled=false")
        elif rule == 6:
            if "manual_request_queued" not in inp:
                failures.append(f"vector {vid!r}: rule 6 input missing 'manual_request_queued'")
            if inp.get("manual_request_queued") is not True:
                failures.append(f"vector {vid!r}: rule 6 input.manual_request_queued must be true")
            if "capacity_available" not in inp:
                failures.append(f"vector {vid!r}: rule 6 input missing 'capacity_available'")
            if inp.get("capacity_available") is not True:
                failures.append(f"vector {vid!r}: rule 6 input.capacity_available must be true "
                                f"(capacity must be available for next-eligible admission)")
            if inp.get("voice_pending") is not True:
                failures.append(f"vector {vid!r}: rule 6 input.voice_pending must be true")
            if inp.get("image_pending") is not True:
                failures.append(f"vector {vid!r}: rule 6 input.image_pending must be true")
            if expected.get("admitted") is not True:
                failures.append(f"vector {vid!r}: rule 6 expected.admitted must be true "
                                f"(manual request becomes the next eligible admission)")
            if expected.get("interrupted") is not False:
                failures.append(f"vector {vid!r}: rule 6 expected.interrupted must be false "
                                f"(queued manual request waits for slot, does not preempt)")
            if expected.get("priority") != "user-initiated":
                failures.append(f"vector {vid!r}: rule 6 expected.priority must be 'user-initiated' "
                                f"(manual request takes priority over voice/image)")
        elif rule == 7:
            if expected.get("counter_unchanged") is not True:
                failures.append(f"vector {vid!r}: rule 7 expected.counter_unchanged must be true")
            before = inp.get("consecutive_voice_admissions_before")
            after = expected.get("consecutive_voice_admissions_after")
            if isinstance(before, int) and isinstance(after, int) and before != after:
                failures.append(f"vector {vid!r}: rule 7 counter must be unchanged "
                                f"(before={before}, after={after})")
        elif rule == 8:
            if expected.get("state_preserved") is not True:
                failures.append(f"vector {vid!r}: rule 8 expected.state_preserved must be true")
        elif rule == 9:
            if expected.get("reconstructed") is not True:
                failures.append(f"vector {vid!r}: rule 9 expected.reconstructed must be true")
            if expected.get("history_matches_durable") is not True:
                failures.append(f"vector {vid!r}: rule 9 expected.history_matches_durable must be true")
            # Counter resets to 0 on restart (validation-contract.md line 134)
            after_counter = expected.get("consecutive_voice_admissions_after")
            if after_counter is None:
                failures.append(f"vector {vid!r}: rule 9 expected missing "
                                f"'consecutive_voice_admissions_after'")
            elif not isinstance(after_counter, int):
                failures.append(f"vector {vid!r}: rule 9 expected.consecutive_voice_admissions_after "
                                f"must be an integer, got {type(after_counter).__name__}")
            elif after_counter != 0:
                failures.append(f"vector {vid!r}: rule 9 expected.consecutive_voice_admissions_after "
                                f"must be 0 (counter resets on restart per validation-contract.md), "
                                f"got {after_counter}")
            # Admission dedup: durable offers and partial-transfer state are
            # reconstructed; the before/after relationship is already enforced
            # via reconstructed + history_matches_durable above. The counter
            # resets to 0 independent of the durable counter value in input.

    # All nine rules must be present
    for rule_num in range(1, 10):
        if rule_num not in rules_found:
            failures.append(f"scheduler rule {rule_num} missing; all nine rules 1-9 required")

    return failures


def _oracle_classify_wav(raw_bytes: bytes) -> tuple[str | None, str | None]:
    """Independent WAV byte oracle: classify raw bytes as pass/fail with exact reason.

    Returns (result, failure_reason) where result is "pass" or "fail".
    For pass, failure_reason is None.  The oracle follows the validation-contract.md
    Error precedence order and does NOT consult any manifest declaration.
    """
    import struct as _struct

    length = len(raw_bytes)

    # --- Precedence 2: file too short (< 44 bytes) ---
    if length < 44:
        return "fail", "too short for WAV header"

    # --- Precedence 3: missing RIFF or WAVE identifiers ---
    if raw_bytes[0:4] != b"RIFF":
        return "fail", "missing RIFF identifier"
    if raw_bytes[8:12] != b"WAVE":
        return "fail", "missing WAVE identifier"

    # --- Precedence 4: RIFF physical size mismatch ---
    riff_size = _struct.unpack_from("<I", raw_bytes, 4)[0]
    if riff_size + 8 != length:
        return "fail", "RIFF size mismatch"

    # --- Size limit: reject files exceeding 9,600,044 bytes ---
    if length > _MAX_RECIPE_BYTE_LENGTH:
        return "fail", "file exceeds maximum WAV size"

    # --- Scan chunks within the RIFF envelope ---
    # Track first occurrence positions and counts for fmt/data.
    fmt_pos = None
    data_pos = None
    fmt_count = 0
    data_count = 0
    fmt_data_raw = None
    data_chunk_size = None
    data_chunk_data_offset = None

    pos = 12
    while pos + 8 <= length:
        chunk_id = raw_bytes[pos:pos + 4]
        chunk_size = _struct.unpack_from("<I", raw_bytes, pos + 4)[0]

        # Bounds: chunk must fit within the file
        chunk_end = pos + 8 + chunk_size
        if chunk_end > length:
            return "fail", "chunk exceeds file bounds"

        if chunk_id == b"fmt ":
            if fmt_pos is None:
                fmt_pos = pos
                fmt_data_raw = raw_bytes[pos + 8:pos + 8 + min(chunk_size, 16)]
            fmt_count += 1
        elif chunk_id == b"data":
            if data_pos is None:
                data_pos = pos
                data_chunk_size = chunk_size
                data_chunk_data_offset = pos + 8
            data_count += 1

        pos += 8 + chunk_size
        # RIFF word-alignment padding
        if chunk_size % 2 != 0:
            pos += 1

    # --- Precedence 5: reordered required chunks ---
    if fmt_pos is not None and data_pos is not None and data_pos < fmt_pos:
        return "fail", "reordered chunks"

    # --- Precedence 5: duplicate required chunks ---
    if fmt_count > 1:
        return "fail", "duplicate fmt chunk"
    if data_count > 1:
        return "fail", "duplicate data chunk"

    # --- Precedence 6: missing required chunks ---
    if fmt_count == 0:
        return "fail", "missing fmt chunk"
    if data_count == 0:
        return "fail", "missing data chunk"

    # --- Nonempty data: reject zero-length data chunk ---
    if data_chunk_size == 0:
        return "fail", "data chunk is empty"

    # --- Parse fmt chunk ---
    if fmt_data_raw is None or len(fmt_data_raw) < 16:
        return "fail", "fmt chunk too short"

    audio_format = _struct.unpack_from("<H", fmt_data_raw, 0)[0]
    num_channels = _struct.unpack_from("<H", fmt_data_raw, 2)[0]
    sample_rate = _struct.unpack_from("<I", fmt_data_raw, 4)[0]
    byte_rate = _struct.unpack_from("<I", fmt_data_raw, 8)[0]
    block_align = _struct.unpack_from("<H", fmt_data_raw, 12)[0]
    bits_per_sample = _struct.unpack_from("<H", fmt_data_raw, 14)[0]

    # --- Precedence 7: unsupported audio format (not PCM) ---
    if audio_format != 1:
        return "fail", "unsupported audio format"

    # --- Precedence 8: incorrect sample rate, channels, or bit depth ---
    if sample_rate != 16000:
        return "fail", "unsupported sample rate"
    if num_channels != 1:
        return "fail", "unsupported channel count"
    if bits_per_sample != 16:
        return "fail", "unsupported bit depth"

    # --- Precedence 9: incorrect block alignment or byte rate ---
    if block_align != 2:
        return "fail", "incorrect block alignment"
    if byte_rate != 32000:
        return "fail", "incorrect byte rate"

    # --- Precedence 10: data chunk truncation or oversize ---
    if data_chunk_size is None or data_chunk_data_offset is None:
        return "fail", "missing data chunk"
    if data_chunk_data_offset + data_chunk_size > length:
        return "fail", "truncated data chunk"

    # --- Precedence 11: odd-byte data chunk ---
    if data_chunk_size % 2 != 0:
        return "fail", "odd-byte data chunk"

    # --- Precedence 12: duration exceeds maximum (300000 ms) ---
    bytes_per_sec = sample_rate * num_channels * (bits_per_sample // 8)
    if bytes_per_sec > 0:
        duration_ms = (data_chunk_size * 1000) // bytes_per_sec
        if duration_ms > 300000:
            return "fail", "duration exceeds maximum"

    # --- Precedence 13: duration inconsistency ---
    # Allow integer-truncation tolerance: duration_ms may round down,
    # so the actual data may be up to (bytes_per_sec / 1000) - 1 bytes
    # larger than what the rounded duration predicts.  Only flag a
    # genuine inconsistency where the data differs by a full ms or more.
    if bytes_per_sec > 0 and duration_ms > 0:
        min_expected = (duration_ms * bytes_per_sec) // 1000
        max_expected = ((duration_ms + 1) * bytes_per_sec) // 1000
        if data_chunk_size < min_expected or data_chunk_size >= max_expected:
            return "fail", "duration inconsistency"

    # --- Precedence 14: trailing bytes after data chunk ---
    data_chunk_end = data_chunk_data_offset + data_chunk_size
    # Align to word boundary (padding byte after data chunk)
    if data_chunk_size % 2 != 0:
        data_chunk_end += 1
    if data_chunk_end < length:
        return "fail", "trailing bytes after data chunk"

    return "pass", None


def check_wav_negative_oracle(manifest: dict, manifest_dir: Path) -> list[str]:
    """Independently classify every committed wav-validation fixture with the
    byte oracle and compare the derived pass/fail and exact failure_reason
    against the manifest declarations.

    The oracle does NOT trust the fixture ID or declared expectations; it
    parses the raw bytes and follows the validation-contract.md Error
    precedence order.  Any discrepancy between the oracle result and the
    manifest is reported as a failure.
    """
    failures = []

    for vector in manifest.get("vectors", []):
        if vector.get("category") != "wav-validation":
            continue
        vid = vector.get("id", "?")
        source = vector.get("source")
        if not isinstance(source, dict):
            continue
        # Skip recipe-only vectors (no committed bytes to classify)
        if "recipe" in source:
            continue
        path_str = source.get("path")
        offset = source.get("offset")
        length = source.get("length")
        if path_str is None or offset is None or length is None:
            continue
        if not isinstance(path_str, str) or not isinstance(offset, int) or not isinstance(length, int):
            continue

        file_path = manifest_dir / path_str
        if not file_path.is_file():
            continue  # already reported by check_vector_sources

        # Read fixture bytes
        try:
            with file_path.open("rb") as fh:
                fh.seek(offset)
                data = fh.read(length)
        except OSError:
            continue  # already reported by check_vector_sources

        if len(data) != length:
            continue

        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            continue

        declared_validation = exp.get("validation")
        declared_reason = exp.get("failure_reason")

        # --- Run the independent oracle ---
        oracle_result, oracle_reason = _oracle_classify_wav(data)

        # --- Compare oracle result against manifest ---
        if oracle_result != declared_validation:
            failures.append(
                f"vector {vid!r}: oracle classified as {oracle_result!r} "
                f"(reason: {oracle_reason!r}) but manifest declares "
                f"validation={declared_validation!r}"
            )
        elif oracle_result == "fail" and oracle_reason != declared_reason:
            failures.append(
                f"vector {vid!r}: oracle failure_reason={oracle_reason!r} "
                f"but manifest declares failure_reason={declared_reason!r}"
            )

    return failures


def _run_oracle_negative_controls() -> list[str]:
    """Construct in-memory adversarial WAVs and verify the oracle rejects them.

    Builds four WAVs with self-consistent RIFF headers, feeds each to
    _oracle_classify_wav, and checks the result against the expected
    pass/fail classification.  The test never consults the manifest; it
    proves pass→fail classification from constructed bytes alone.

    Tests:
      * 9,600,046-byte WAV (2 bytes over the 9,600,044 limit)
      * 9,600,074-byte WAV (30 bytes over the limit)
      * 44-byte zero-length-data-chunk WAV
      * 9,600,044-byte valid-boundary WAV (must pass all checks)

    Returns a list of failure descriptions (empty on success).
    """
    import struct as _struct

    failures: list[str] = []

    def _build_header(data_bytes: int) -> bytes:
        """Return a canonical 44-byte WAV header with self-consistent RIFF."""
        total = 44 + data_bytes
        riff_size = total - 8
        header = bytearray()
        header.extend(b"RIFF")
        header.extend(_struct.pack("<I", riff_size))
        header.extend(b"WAVE")
        header.extend(b"fmt ")
        header.extend(_struct.pack("<I", 16))  # PCM fmt chunk size
        header.extend(_struct.pack("<H", 1))   # audio format PCM
        header.extend(_struct.pack("<H", 1))   # mono
        header.extend(_struct.pack("<I", 16000))  # sample rate
        header.extend(_struct.pack("<I", 32000))  # byte rate
        header.extend(_struct.pack("<H", 2))      # block align
        header.extend(_struct.pack("<H", 16))     # bits per sample
        header.extend(b"data")
        header.extend(_struct.pack("<I", data_bytes))
        return bytes(header)

    def _check(name: str, data_bytes: int, expected_result: str,
               expected_reason: str | None) -> None:
        """Build a WAV with *data_bytes* of zero fill and classify it."""
        header = _build_header(data_bytes)
        raw = header + b"\x00" * data_bytes
        result, reason = _oracle_classify_wav(raw)
        if result != expected_result:
            failures.append(
                f"oracle negative control {name!r}: expected "
                f"{expected_result!r}, got {result!r} (reason: {reason!r})"
            )
        elif expected_reason is not None and reason != expected_reason:
            failures.append(
                f"oracle negative control {name!r}: expected reason "
                f"{expected_reason!r}, got {reason!r}"
            )

    # Adversarial: 9,600,046 bytes (2 over limit)
    _check("9600046-over-limit", 9_600_002,
           "fail", "file exceeds maximum WAV size")

    # Adversarial: 9,600,074 bytes (30 over limit)
    _check("9600074-over-limit", 9_600_030,
           "fail", "file exceeds maximum WAV size")

    # Adversarial: 44-byte zero-data WAV
    _check("zero-data-chunk", 0,
           "fail", "data chunk is empty")

    # Boundary: exactly 9,600,044 bytes — must pass
    _check("9600044-boundary-pass", _MAX_RECIPE_DATA_BYTES,
           "pass", None)

    return failures


def check_wav_fixture_validation(manifest: dict, manifest_dir: Path) -> list[str]:
    """Cross-validate WAV vector expectations against actual fixture bytes.

    For every wav-validation vector with a committed byte-range source, parse
    the WAV header from the fixture and reject any expectation that contradicts
    the binary content.
    """
    import struct as _struct
    failures = []

    for vector in manifest.get("vectors", []):
        if vector.get("category") != "wav-validation":
            continue
        vid = vector.get("id", "?")
        source = vector.get("source")
        if not isinstance(source, dict):
            continue
        path_str = source.get("path")
        offset = source.get("offset")
        length = source.get("length")
        if path_str is None or offset is None or length is None:
            continue
        if not isinstance(path_str, str) or not isinstance(offset, int) or not isinstance(length, int):
            continue

        file_path = manifest_dir / path_str
        if not file_path.is_file():
            continue  # already reported by check_vector_sources

        # Read fixture bytes
        try:
            with file_path.open("rb") as fh:
                fh.seek(offset)
                data = fh.read(length)
        except OSError:
            continue  # already reported by check_vector_sources

        if len(data) != length:
            continue

        exp = vector.get("expectations")
        if not isinstance(exp, dict):
            continue

        # --- Parse WAV header ---
        # Minimum WAV header is 44 bytes (RIFF + fmt + data with zero data)
        if len(data) < 44:
            # For vectors expected to fail validation, skip fixture cross-check
            # when the fixture is too short to parse (e.g., empty file, truncated)
            if exp.get("validation") == "fail":
                continue
            failures.append(f"vector {vid!r}: fixture too short ({len(data)} bytes) "
                            f"to parse WAV header for a pass-expected vector")
            continue

        try:
            riff_id = data[0:4]
            if riff_id != b"RIFF":
                if exp.get("validation") == "fail":
                    continue  # expected failure, no cross-check needed
                failures.append(f"vector {vid!r}: fixture missing RIFF identifier but "
                                f"expectations declare validation=pass")
                continue

            wave_id = data[8:12]
            if wave_id != b"WAVE":
                if exp.get("validation") == "fail":
                    continue
                failures.append(f"vector {vid!r}: fixture missing WAVE identifier but "
                                f"expectations declare validation=pass")
                continue

            # Find fmt chunk
            pos = 12
            fmt_found = False
            data_found = False
            fmt_data = None
            data_chunk_offset = None
            data_chunk_size = None

            while pos + 8 <= len(data):
                chunk_id = data[pos:pos + 4]
                chunk_size = _struct.unpack_from("<I", data, pos + 4)[0]
                if chunk_id == b"fmt " and not fmt_found:
                    fmt_data = data[pos + 8:pos + 8 + min(chunk_size, 16)]
                    fmt_found = True
                elif chunk_id == b"data" and not data_found:
                    data_chunk_offset = pos + 8
                    data_chunk_size = chunk_size
                    data_found = True
                pos += 8 + chunk_size
                # RIFF chunks are word-aligned with padding
                if chunk_size % 2 != 0:
                    pos += 1

            if not fmt_found:
                if exp.get("validation") == "fail":
                    continue
                failures.append(f"vector {vid!r}: fixture has no fmt chunk but "
                                f"expectations declare validation=pass")
                continue

            if not data_found:
                if exp.get("validation") == "fail":
                    continue
                failures.append(f"vector {vid!r}: fixture has no data chunk but "
                                f"expectations declare validation=pass")
                continue

            # Parse fmt chunk
            if len(fmt_data) < 16:
                failures.append(f"vector {vid!r}: fmt chunk too short ({len(fmt_data)} bytes)")
                continue

            audio_format = _struct.unpack_from("<H", fmt_data, 0)[0]
            num_channels = _struct.unpack_from("<H", fmt_data, 2)[0]
            sample_rate = _struct.unpack_from("<I", fmt_data, 4)[0]
            byte_rate = _struct.unpack_from("<I", fmt_data, 8)[0]
            block_align = _struct.unpack_from("<H", fmt_data, 12)[0]
            bits_per_sample = _struct.unpack_from("<H", fmt_data, 14)[0]

        except (struct.error, IndexError) as exc:
            failures.append(f"vector {vid!r}: error parsing WAV header: {exc}")
            continue

        # --- Cross-validate expectations against parsed header ---
        exp_sample_rate = exp.get("sample_rate")
        exp_channels = exp.get("channels")
        exp_bits = exp.get("bits_per_sample")
        exp_data_bytes = exp.get("data_bytes")
        exp_total_bytes = exp.get("total_bytes")
        exp_duration_ms = exp.get("duration_ms")
        exp_validation = exp.get("validation")

        # For pass-expected vectors, validate all declared fields match fixture
        if exp_validation == "pass":
            if exp_sample_rate is not None and exp_sample_rate != sample_rate:
                failures.append(f"vector {vid!r}: expected sample_rate={exp_sample_rate} "
                                f"but fixture has {sample_rate}")
            if exp_channels is not None and exp_channels != num_channels:
                failures.append(f"vector {vid!r}: expected channels={exp_channels} "
                                f"but fixture has {num_channels}")
            if exp_bits is not None and exp_bits != bits_per_sample:
                failures.append(f"vector {vid!r}: expected bits_per_sample={exp_bits} "
                                f"but fixture has {bits_per_sample}")
            if exp_data_bytes is not None and exp_data_bytes != data_chunk_size:
                failures.append(f"vector {vid!r}: expected data_bytes={exp_data_bytes} "
                                f"but fixture data chunk size is {data_chunk_size}")
            if exp_total_bytes is not None and exp_total_bytes != length:
                failures.append(f"vector {vid!r}: expected total_bytes={exp_total_bytes} "
                                f"but fixture length is {length}")

            # Validate computed duration
            if exp_duration_ms is not None and sample_rate > 0 and bits_per_sample > 0 and num_channels > 0:
                bytes_per_sample = (bits_per_sample // 8) * num_channels
                if bytes_per_sample > 0:
                    computed_duration_ms = (data_chunk_size * 1000) // (sample_rate * bytes_per_sample)
                    if exp_duration_ms != computed_duration_ms:
                        failures.append(f"vector {vid!r}: expected duration_ms={exp_duration_ms} "
                                        f"but fixture computes to {computed_duration_ms} "
                                        f"(data={data_chunk_size} bytes, rate={sample_rate}, "
                                        f"bps={bits_per_sample}, ch={num_channels})")

        # For fail-expected vectors, validate that the fixture actually has the
        # declared failure-causing property when the expectations declare
        # specific metadata that contradicts the fixture.
        elif exp_validation == "fail":
            # If expectations declare specific metadata (sample_rate, etc.) for a
            # fail vector, those values describe what the fixture contains, not
            # what is required. Cross-check them against the fixture.
            if exp_sample_rate is not None and exp_sample_rate != sample_rate:
                failures.append(f"vector {vid!r}: declared sample_rate={exp_sample_rate} "
                                f"but fixture has {sample_rate}")
            if exp_channels is not None and exp_channels != num_channels:
                failures.append(f"vector {vid!r}: declared channels={exp_channels} "
                                f"but fixture has {num_channels}")
            if exp_bits is not None and exp_bits != bits_per_sample:
                failures.append(f"vector {vid!r}: declared bits_per_sample={exp_bits} "
                                f"but fixture has {bits_per_sample}")

    return failures


def check_metadata_sources_null(manifest: dict, _manifest_dir: Path) -> list[str]:
    """Reject non-null sources for metadata-only categories (seek, marker, receiver, registry, scheduler)."""
    failures = []
    for vector in manifest.get("vectors", []):
        cat = vector.get("category")
        if cat not in ("seek", "marker", "receiver", "registry", "scheduler"):
            continue
        vid = vector.get("id", "?")
        source = vector.get("source")
        if source is not None:
            failures.append(f"vector {vid!r}: {cat} source must be null (metadata-only), "
                            f"got {type(source).__name__}")
    return failures


VECTOR_CHECKS = (
    ("manifest schema version", check_manifest_schema_version),
    ("manifest structure", check_manifest_structure),
    ("vector ids unique", check_vector_ids_unique),
    ("vector categories", check_vector_categories),
    ("category coverage", check_category_coverage),
    ("vector paths", check_vector_paths),
    ("vector sources", check_vector_sources),
    ("wav sources committed", check_wav_sources_committed),
    ("recipe integrity", check_recipe_integrity),
    ("pcm sources committed", check_pcm_sources_committed),
    ("pcm expectations", check_pcm_expectations),
    ("seek expectations", check_seek_expectations),
    ("marker expectations", check_marker_expectations),
    ("receiver expectations", check_receiver_expectations),
    ("registry expectations", check_registry_expectations),
    ("registry crash step coverage", check_registry_crash_step_coverage),
    ("scheduler expectations", check_scheduler_expectations),
    ("wav fixture validation", check_wav_fixture_validation),
    ("wav negative oracle", check_wav_negative_oracle),
    ("metadata sources null", check_metadata_sources_null),
    ("no platform overrides", check_no_platform_overrides),
)

# Check groups that require --validation to be active.
VALIDATION_ONLY_CHECKS = frozenset({"wav fixture validation", "wav negative oracle"})


# --- I03 vector mutation self-test ---

def _mutate_version_zero(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    mutated["schema_version"] = 0
    return mutated


def _mutate_duplicate_first_id(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    if len(vectors) < 2:
        return None
    mutated = _json.loads(_json.dumps(manifest))
    mutated["vectors"][1]["id"] = mutated["vectors"][0]["id"]
    return mutated


def _mutate_add_escape_path(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    if not vectors:
        return None
    mutated = _json.loads(_json.dumps(manifest))
    mutated["vectors"][0]["source"] = {"path": "../escape.wav"}
    return mutated


def _mutate_bad_category(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    if not vectors:
        return None
    mutated = _json.loads(_json.dumps(manifest))
    mutated["vectors"][0]["category"] = "nonexistent-category"
    return mutated


def _mutate_add_platform_key(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    if not vectors:
        return None
    mutated = _json.loads(_json.dumps(manifest))
    mutated["vectors"][0]["windows_path"] = "C:\\evil.wav"
    return mutated


def _mutate_null_wav_source(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "wav-validation" and v.get("source") is not None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"] = None
                    return mutated
    return None


def _mutate_wrong_sha256(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        src = v.get("source")
        if isinstance(src, dict) and "sha256" in src:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"]["sha256"] = "0" * 64
                    return mutated
    return None


def _mutate_oob_offset(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        src = v.get("source")
        if isinstance(src, dict) and "offset" in src and isinstance(src.get("offset"), int):
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"]["offset"] = 999_999_999
                    return mutated
    return None


def _mutate_missing_path_wav(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "wav-validation":
            src = v.get("source")
            if isinstance(src, dict) and "path" in src and "recipe" not in src:
                mutated = _json.loads(_json.dumps(manifest))
                for mv in mutated["vectors"]:
                    if mv["id"] == v["id"]:
                        del mv["source"]["path"]
                        return mutated
    return None


def _mutate_null_pcm_source(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "pcm-frames" and v.get("source") is not None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"] = None
                    return mutated
    return None


def _mutate_missing_frame_count(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "pcm-frames":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    del mv["expectations"]["frame_count"]
                    return mutated
    return None


def _mutate_terminal_no_reason(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "pcm-frames" and v.get("expectations", {}).get("terminal_failure"):
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    del mv["expectations"]["failure_reason"]
                    return mutated
    return None


def _mutate_odd_pending_not_bool(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "pcm-frames" and v.get("expectations", {}).get("odd_byte_pending") is not None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["odd_byte_pending"] = "yes"
                    return mutated
    return None


def _mutate_devicefailure_no_terminal(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        events = exp.get("events", [])
        if v.get("category") == "pcm-frames" and "DeviceFailure" in events and exp.get("terminal_failure"):
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["terminal_failure"] = False
                    return mutated
    return None


def _mutate_seek_missing_duration(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "seek" and "duration_ms" in v.get("expectations", {}):
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    del mv["expectations"]["duration_ms"]
                    return mutated
    return None


def _mutate_seek_wrong_alignment(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "seek" and exp.get("state") == "ready" and "aligned_byte" in exp:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    # Make aligned_byte odd to break even-alignment
                    mv["expectations"]["aligned_byte"] = mv["expectations"]["aligned_byte"] + 1
                    return mutated
    return None


def _mutate_marker_missing_parses(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "marker":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    del mv["expectations"]["marker_parses"]
                    return mutated
    return None


def _mutate_marker_bad_classification(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "marker":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["classification"] = "invalid_state"
                    return mutated
    return None


def _mutate_seek_non_null_source(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "seek" and v.get("source") is None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"] = {"path": "fake.wav"}
                    return mutated
    return None


def _mutate_receiver_missing_initial_state(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "receiver":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    del mv["expectations"]["initial_state"]
                    return mutated
    return None


def _mutate_receiver_bad_state(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "receiver":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["resulting_state"] = "InvalidState"
                    return mutated
    return None


def _mutate_receiver_refetch_on_invalid(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "receiver" and exp.get("resulting_state") == "Invalid marked content":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["refetch_allowed"] = True
                    return mutated
    return None


def _mutate_registry_missing_sub_category(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "registry":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    del mv["expectations"]["sub_category"]
                    return mutated
    return None


def _mutate_registry_bad_step(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "registry" and exp.get("sub_category") == "crash-boundary":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["step"] = 99
                    return mutated
    return None


def _mutate_registry_dup_send_true(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "registry" and exp.get("sub_category") == "crash-boundary":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["duplicate_send_possible"] = True
                    return mutated
    return None


def _mutate_registry_plaintext_dep_true(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "registry" and exp.get("sub_category") == "crash-boundary":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["plaintext_dependency"] = True
                    return mutated
    return None


def _mutate_receiver_non_null_source(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "receiver" and v.get("source") is None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"] = {"path": "fake.wav"}
                    return mutated
    return None


def _mutate_registry_non_null_source(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "registry" and v.get("source") is None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"] = {"path": "fake.wav"}
                    return mutated
    return None


def _mutate_missing_contract_version(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    del mutated["contract_version"]
    return mutated


def _mutate_wrong_contract_version(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    mutated["contract_version"] = 99
    return mutated


def _mutate_noninteger_contract_version(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    mutated["contract_version"] = "1"
    return mutated


def _mutate_missing_pcm_contract_version(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    del mutated["pcm_contract_version"]
    return mutated


def _mutate_wrong_validation_contract_version(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    mutated["validation_contract_version"] = 0
    return mutated


def _mutate_runner_schema_too_high(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    mutated["min_runner_schema"] = 999
    return mutated


def _mutate_missing_category_scheduler(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    if "scheduler" in mutated.get("categories", {}):
        del mutated["categories"]["scheduler"]
    return mutated


def _mutate_unknown_category(manifest: dict) -> dict | None:
    import json as _json
    mutated = _json.loads(_json.dumps(manifest))
    mutated["categories"]["fake-category"] = "not a real category"
    return mutated


def _mutate_delete_crash_step_5(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "registry" and exp.get("sub_category") == "crash-boundary" and exp.get("step") == 5:
            mutated = _json.loads(_json.dumps(manifest))
            mutated["vectors"] = [mv for mv in mutated["vectors"] if mv["id"] != v["id"]]
            return mutated
    return None


def _mutate_delete_scheduler_rule_4(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 4:
            mutated = _json.loads(_json.dumps(manifest))
            mutated["vectors"] = [mv for mv in mutated["vectors"] if mv["id"] != v["id"]]
            return mutated
    return None


def _mutate_scheduler_bad_rule_value(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "scheduler":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["rule"] = 99
                    return mutated
    return None


def _mutate_scheduler_missing_input(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "scheduler":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    del mv["expectations"]["input"]
                    return mutated
    return None


def _mutate_scheduler_non_null_source(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("category") == "scheduler" and v.get("source") is None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"] = {"path": "fake.wav"}
                    return mutated
    return None


def _mutate_scheduler_rule4_priority_voice(manifest: dict) -> dict | None:
    """Change rule 4 expected.priority from 'image' to 'voice' — contradicts 3:1 fairness."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 4:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["expected"]["priority"] = "voice"
                    return mutated
    return None


def _mutate_scheduler_rule6_capacity_false(manifest: dict) -> dict | None:
    """Change rule 6 input.capacity_available to false while admitted remains true — incoherent."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 6:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["input"]["capacity_available"] = False
                    return mutated
    return None


def _mutate_scheduler_rule6_priority_voice(manifest: dict) -> dict | None:
    """Change rule 6 expected.priority from 'user-initiated' to 'voice' — manual-next must take priority over voice."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 6:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["expected"]["priority"] = "voice"
                    return mutated
    return None


def _mutate_scheduler_rule6_priority_image(manifest: dict) -> dict | None:
    """Change rule 6 expected.priority from 'user-initiated' to 'image' — manual-next must take priority over image."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 6:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["expected"]["priority"] = "image"
                    return mutated
    return None


def _mutate_scheduler_rule6_admitted_false(manifest: dict) -> dict | None:
    """Change rule 6 expected.admitted from true to false — contradicts manual-next-eligible semantics."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 6:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["expected"]["admitted"] = False
                    return mutated
    return None


def _mutate_scheduler_rule6_interrupted_true(manifest: dict) -> dict | None:
    """Change rule 6 expected.interrupted from false to true — queued manual request does not preempt."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 6:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["expected"]["interrupted"] = True
                    return mutated
    return None


def _mutate_scheduler_rule9_restart_counter_2(manifest: dict) -> dict | None:
    """Change rule 9 consecutive_voice_admissions_after from 0 to 2 — counter must reset to 0 on restart."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "scheduler" and exp.get("rule") == 9:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["expected"]["consecutive_voice_admissions_after"] = 2
                    return mutated
    return None


def _mutate_recipe_wrong_name(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        src = v.get("source")
        if isinstance(src, dict) and src.get("recipe") == _MAX_RECIPE_NAME:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"]["recipe"] = "bad-recipe"
                    return mutated
    return None


def _mutate_recipe_wrong_hash(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        src = v.get("source")
        if isinstance(src, dict) and "expected_sha256" in src and src.get("recipe") is not None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"]["expected_sha256"] = "0" * 64
                    return mutated
    return None


def _mutate_recipe_wrong_length(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        src = v.get("source")
        if isinstance(src, dict) and "expected_byte_length" in src and src.get("recipe") is not None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["source"]["expected_byte_length"] = 9999999
                    return mutated
    return None


def _mutate_recipe_bogus_data_bytes(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        src = v.get("source")
        if isinstance(src, dict) and src.get("recipe") is not None:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["data_bytes"] = 999
                    return mutated
    return None


def _mutate_oracle_wrong_negative_reason(manifest: dict) -> dict | None:
    """Change a fail vector's failure_reason to a wrong value."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("validation") == "fail" and "failure_reason" in exp:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["failure_reason"] = "unsupported sample rate"
                    return mutated
    return None


def _mutate_oracle_pass_flipped_to_fail(manifest: dict) -> dict | None:
    """Change a pass vector to fail with a bogus reason."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("validation") == "pass":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["validation"] = "fail"
                    mv["expectations"]["failure_reason"] = "bogus reason"
                    return mutated
    return None


def _mutate_oracle_bad_riff_size_reason(manifest: dict) -> dict | None:
    """Change the RIFF-size-mismatch vector's failure_reason to something else."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("failure_reason") == "RIFF size mismatch":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["failure_reason"] = "truncated data chunk"
                    return mutated
    return None


def _mutate_oracle_duplicate_wrong_reason(manifest: dict) -> dict | None:
    """Change the duplicate-chunk vector's failure_reason to a wrong value."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("failure_reason") == "duplicate fmt chunk":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["failure_reason"] = "reordered chunks"
                    return mutated
    return None


def _mutate_oracle_trailing_wrong_reason(manifest: dict) -> dict | None:
    """Change the trailing-bytes vector's failure_reason to a wrong value."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("failure_reason") == "trailing bytes after data chunk":
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["failure_reason"] = "RIFF size mismatch"
                    return mutated
    return None


def _mutate_pcm_short_final_wrong_last_ts(manifest: dict) -> dict | None:
    """Flip pcm-short-final last_timestamp_ns to 20125000 — violates completed_samples * 62500."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("id") == "pcm-short-final" and v.get("category") == "pcm-frames":
            exp = v.get("expectations", {})
            if "last_timestamp_ns" in exp and exp["last_timestamp_ns"] != 20125000:
                mutated = _json.loads(_json.dumps(manifest))
                for mv in mutated["vectors"]:
                    if mv["id"] == "pcm-short-final":
                        mv["expectations"]["last_timestamp_ns"] = 20125000
                        return mutated
    return None


def _mutate_seek_one_ms_wrong_effective_ns(manifest: dict) -> dict | None:
    """Change seek-one-ms-aligned effective_ns from 1000000 to 0 (old wrong value)."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("id") == "seek-one-ms-aligned" and v.get("category") == "seek":
            exp = v.get("expectations", {})
            if exp.get("effective_ns") != 0:
                mutated = _json.loads(_json.dumps(manifest))
                for mv in mutated["vectors"]:
                    if mv["id"] == "seek-one-ms-aligned":
                        mv["expectations"]["effective_ns"] = 0
                        return mutated
    return None


def _mutate_seek_one_ms_wrong_aligned_byte(manifest: dict) -> dict | None:
    """Change seek-one-ms-aligned aligned_byte from 76 to 44 (old wrong value)."""
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        if v.get("id") == "seek-one-ms-aligned" and v.get("category") == "seek":
            exp = v.get("expectations", {})
            if exp.get("aligned_byte") != 44:
                mutated = _json.loads(_json.dumps(manifest))
                for mv in mutated["vectors"]:
                    if mv["id"] == "seek-one-ms-aligned":
                        mv["expectations"]["aligned_byte"] = 44
                        return mutated
    return None


VECTOR_MUTATIONS = (
    ("change schema_version to 0", "manifest schema version", _mutate_version_zero),
    ("duplicate first vector id", "vector ids unique", _mutate_duplicate_first_id),
    ("add dot-dot path escape", "vector paths", _mutate_add_escape_path),
    ("use undeclared category", "vector categories", _mutate_bad_category),
    ("add windows_path override", "no platform overrides", _mutate_add_platform_key),
    ("null wav-validation source", "wav sources committed", _mutate_null_wav_source),
    ("wrong sha256 in source", "vector sources", _mutate_wrong_sha256),
    ("out-of-bounds offset", "vector sources", _mutate_oob_offset),
    ("missing path on wav source", "wav sources committed", _mutate_missing_path_wav),
    ("null pcm-frames source", "pcm sources committed", _mutate_null_pcm_source),
    ("missing frame_count in pcm expectations", "pcm expectations", _mutate_missing_frame_count),
    ("terminal_failure without failure_reason", "pcm expectations", _mutate_terminal_no_reason),
    ("odd_byte_pending set to non-boolean", "pcm expectations", _mutate_odd_pending_not_bool),
    ("pcm short-final wrong last_timestamp_ns", "pcm expectations", _mutate_pcm_short_final_wrong_last_ts),
    ("DeviceFailure without terminal_failure", "pcm expectations", _mutate_devicefailure_no_terminal),
    ("seek missing duration_ms", "seek expectations", _mutate_seek_missing_duration),
    ("seek wrong aligned_byte alignment", "seek expectations", _mutate_seek_wrong_alignment),
    ("seek one-ms wrong effective_ns", "seek expectations", _mutate_seek_one_ms_wrong_effective_ns),
    ("seek one-ms wrong aligned_byte", "seek expectations", _mutate_seek_one_ms_wrong_aligned_byte),
    ("marker missing marker_parses", "marker expectations", _mutate_marker_missing_parses),
    ("marker bad classification", "marker expectations", _mutate_marker_bad_classification),
    ("seek non-null source", "metadata sources null", _mutate_seek_non_null_source),
    ("receiver missing initial_state", "receiver expectations", _mutate_receiver_missing_initial_state),
    ("receiver bad resulting_state", "receiver expectations", _mutate_receiver_bad_state),
    ("receiver refetch on invalid", "receiver expectations", _mutate_receiver_refetch_on_invalid),
    ("registry missing sub_category", "registry expectations", _mutate_registry_missing_sub_category),
    ("registry bad step value", "registry expectations", _mutate_registry_bad_step),
    ("registry duplicate_send_possible true", "registry expectations", _mutate_registry_dup_send_true),
    ("registry plaintext_dependency true", "registry expectations", _mutate_registry_plaintext_dep_true),
    ("receiver non-null source", "metadata sources null", _mutate_receiver_non_null_source),
    ("registry non-null source", "metadata sources null", _mutate_registry_non_null_source),
    ("missing contract_version pin", "manifest schema version", _mutate_missing_contract_version),
    ("wrong contract_version value", "manifest schema version", _mutate_wrong_contract_version),
    ("non-integer contract_version", "manifest schema version", _mutate_noninteger_contract_version),
    ("missing pcm_contract_version pin", "manifest schema version", _mutate_missing_pcm_contract_version),
    ("wrong validation_contract_version value", "manifest schema version", _mutate_wrong_validation_contract_version),
    ("min_runner_schema too high", "manifest schema version", _mutate_runner_schema_too_high),
    ("missing scheduler category", "category coverage", _mutate_missing_category_scheduler),
    ("unknown category declared", "category coverage", _mutate_unknown_category),
    ("delete registry crash step 5", "registry crash step coverage", _mutate_delete_crash_step_5),
    ("delete scheduler rule 4", "scheduler expectations", _mutate_delete_scheduler_rule_4),
    ("scheduler bad rule value", "scheduler expectations", _mutate_scheduler_bad_rule_value),
    ("scheduler missing input dict", "scheduler expectations", _mutate_scheduler_missing_input),
    ("scheduler non-null source", "metadata sources null", _mutate_scheduler_non_null_source),
    ("scheduler rule 4 priority voice", "scheduler expectations", _mutate_scheduler_rule4_priority_voice),
    ("scheduler rule 6 capacity false", "scheduler expectations", _mutate_scheduler_rule6_capacity_false),
    ("scheduler rule 6 priority voice", "scheduler expectations", _mutate_scheduler_rule6_priority_voice),
    ("scheduler rule 6 priority image", "scheduler expectations", _mutate_scheduler_rule6_priority_image),
    ("scheduler rule 6 admitted false", "scheduler expectations", _mutate_scheduler_rule6_admitted_false),
    ("scheduler rule 6 interrupted true", "scheduler expectations", _mutate_scheduler_rule6_interrupted_true),
    ("scheduler rule 9 restart counter 2", "scheduler expectations", _mutate_scheduler_rule9_restart_counter_2),
    ("recipe wrong name", "recipe integrity", _mutate_recipe_wrong_name),
    ("recipe wrong hash", "recipe integrity", _mutate_recipe_wrong_hash),
    ("recipe wrong length", "recipe integrity", _mutate_recipe_wrong_length),
    ("recipe bogus data_bytes", "recipe integrity", _mutate_recipe_bogus_data_bytes),
    ("oracle wrong negative reason", "wav negative oracle", _mutate_oracle_wrong_negative_reason),
    ("oracle pass flipped to fail", "wav negative oracle", _mutate_oracle_pass_flipped_to_fail),
    ("oracle bad RIFF size reason", "wav negative oracle", _mutate_oracle_bad_riff_size_reason),
    ("oracle duplicate wrong reason", "wav negative oracle", _mutate_oracle_duplicate_wrong_reason),
    ("oracle trailing wrong reason", "wav negative oracle", _mutate_oracle_trailing_wrong_reason),
)


def _mutate_wav_wrong_sample_rate(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("validation") == "pass" and "sample_rate" in exp:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["sample_rate"] = 44100
                    return mutated
    return None


def _mutate_wav_wrong_data_bytes(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("validation") == "pass" and "data_bytes" in exp:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["data_bytes"] = mv["expectations"]["data_bytes"] + 100
                    return mutated
    return None


def _mutate_wav_wrong_total_bytes(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("validation") == "pass" and "total_bytes" in exp:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["total_bytes"] = mv["expectations"]["total_bytes"] + 50
                    return mutated
    return None


def _mutate_wav_wrong_duration(manifest: dict) -> dict | None:
    import json as _json
    vectors = manifest.get("vectors", [])
    for v in vectors:
        exp = v.get("expectations", {})
        if v.get("category") == "wav-validation" and exp.get("validation") == "pass" and "duration_ms" in exp:
            mutated = _json.loads(_json.dumps(manifest))
            for mv in mutated["vectors"]:
                if mv["id"] == v["id"]:
                    mv["expectations"]["duration_ms"] = mv["expectations"]["duration_ms"] + 10
                    return mutated
    return None


VALIDATION_MUTATIONS = (
    ("wav wrong sample_rate in expectations", "wav fixture validation", _mutate_wav_wrong_sample_rate),
    ("wav wrong data_bytes in expectations", "wav fixture validation", _mutate_wav_wrong_data_bytes),
    ("wav wrong total_bytes in expectations", "wav fixture validation", _mutate_wav_wrong_total_bytes),
    ("wav wrong duration_ms in expectations", "wav fixture validation", _mutate_wav_wrong_duration),
)


def run_vector_mutation_self_test(manifest: dict, manifest_dir: Path,
                                  validation: bool = False) -> list[str]:
    """Require every active vector mutation to flip its expected check group."""
    failures = []
    baseline = [(group, check(manifest, manifest_dir)) for group, check in VECTOR_CHECKS]
    for group, messages in baseline:
        failures.extend(f"vector baseline {group}: {msg}" for msg in messages)
    if failures:
        return failures

    mutations = list(VECTOR_MUTATIONS)
    if validation:
        mutations.extend(VALIDATION_MUTATIONS)

    for name, expected_group, transform in mutations:
        mutated = transform(manifest)
        if mutated is None:
            failures.append(f"vector mutation {name!r}: could not apply transform")
            continue
        group_failures = {group: check(mutated, manifest_dir)
                          for group, check in VECTOR_CHECKS}
        if not group_failures.get(expected_group):
            failures.append(f"vector mutation {name!r} still passes; expected check "
                            f"group {expected_group!r} to fail")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--contract", type=Path, default=Path(__file__).with_name("contract.md"),
        help="I01 contract document to check (default: contract.md beside this script)")
    parser.add_argument(
        "--pcm", action="store_true",
        help="also check the I02 PCM interface contract (runs the I01 checks first)")
    parser.add_argument(
        "--pcm-contract", type=Path,
        default=Path(__file__).with_name("pcm-contract.md"),
        help="I02 PCM contract document (default: pcm-contract.md beside this script)")
    parser.add_argument(
        "--mutation-self-test", action="store_true",
        help="apply regression mutations to in-memory copies and require the "
             "expected check group to fail for each")
    parser.add_argument(
        "--vectors", action="store_true",
        help="also check the I03 vector manifest (runs the I01 checks first)")
    parser.add_argument(
        "--validation", action="store_true",
        help="also check the I04 validation contract and cross-validate WAV "
             "vector expectations against actual fixture bytes (WAV fixture "
             "validation requires --vectors)")
    parser.add_argument(
        "--validation-contract", type=Path,
        default=Path(__file__).with_name("validation-contract.md"),
        help="I04 validation contract document (default: validation-contract.md "
             "beside this script)")
    parser.add_argument(
        "--manifest", type=Path,
        default=Path(__file__).parent / "vectors" / "manifest.json",
        help="I03 vector manifest (default: vectors/manifest.json beside this script)")
    args = parser.parse_args()

    if args.validation and not args.vectors:
        # WAV fixture validation requires --vectors, but validation-contract.md
        # checks do not.  Proceed with validation-contract.md only.
        pass

    targets = [("contract", args.contract)]
    if args.pcm:
        targets.append(("pcm", args.pcm_contract))
    if args.validation:
        targets.append(("validation", args.validation_contract))
    documents = {}
    for doc_key, path in targets:
        try:
            documents[doc_key] = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        except OSError as exc:
            print(f"FAIL: cannot read {doc_key} contract at {path}: {exc}")
            return 1

    failures = [f"{doc_key} {group}: {message}"
                for doc_key, text in documents.items()
                for group, check in CHECK_SETS[doc_key]
                for message in check(text)]
    if failures:
        for message in failures:
            print("FAIL: " + message)
        return 1

    if args.mutation_self_test:
        mutation_failures = run_mutation_self_test(documents)
        if mutation_failures:
            for message in mutation_failures:
                print("FAIL: " + message)
            return 1
        active = sum(1 for mutation in MUTATIONS if mutation[1] in documents)
        print(f"PASS: {active} regression mutations each flipped their expected "
              f"check group to failing")

        if args.validation:
            vc_failures = run_validation_contract_mutation_self_test(
                documents.get("validation", ""))
            if vc_failures:
                for message in vc_failures:
                    print("FAIL: " + message)
                return 1
            print(f"PASS: {len(VALIDATION_CONTRACT_MUTATIONS)} validation contract "
                  f"regression mutations each flipped their expected check group "
                  f"to failing")

    if args.vectors:
        manifest = load_manifest(args.manifest)
        manifest_dir = args.manifest.parent.resolve()
        active_checks = [(group, check) for group, check in VECTOR_CHECKS
                         if args.validation or group not in VALIDATION_ONLY_CHECKS]
        vector_failures = [f"vectors {group}: {message}"
                           for group, check in active_checks
                           for message in check(manifest, manifest_dir)]
        if vector_failures:
            for message in vector_failures:
                print("FAIL: " + message)
            return 1

        if args.mutation_self_test:
            vm_failures = run_vector_mutation_self_test(
                manifest, manifest_dir, validation=args.validation)
            if vm_failures:
                for message in vm_failures:
                    print("FAIL: " + message)
                return 1
            total_mutations = len(VECTOR_MUTATIONS)
            if args.validation:
                total_mutations += len(VALIDATION_MUTATIONS)
            print(f"PASS: {total_mutations} vector regression mutations each "
                  f"flipped their expected check group to failing")

            if args.validation:
                oracle_nc_failures = _run_oracle_negative_controls()
                if oracle_nc_failures:
                    for message in oracle_nc_failures:
                        print("FAIL: " + message)
                    return 1
                print("PASS: oracle negative controls correctly classify "
                      "all adversarial samples")

    checked = " + ".join(path.name for _, path in targets)
    if args.vectors:
        checked += " + " + args.manifest.name
    total_vector_checks = len(VECTOR_CHECKS) if args.validation else len(VECTOR_CHECKS) - len(VALIDATION_ONLY_CHECKS)
    total_checks = len(CHECKS)
    if args.pcm:
        total_checks += len(PCM_CHECKS)
    if args.vectors:
        total_checks += total_vector_checks
    if args.validation:
        total_checks += len(VALIDATION_CONTRACT_CHECKS)

    parts = [f"contract version {CONTRACT_VERSION}"]
    if args.pcm:
        parts.append(f"pcm-contract version {PCM_CONTRACT_VERSION}")
    if args.vectors:
        parts.append(f"manifest schema version {MANIFEST_SCHEMA_VERSION}")
    if args.validation:
        parts.append(f"validation-contract version {VALIDATION_CONTRACT_VERSION}")
    version_info = ", ".join(parts)

    desc_parts = ["I01 shared-contract"]
    if args.pcm:
        desc_parts.append("I02 PCM interface")
    if args.vectors:
        desc_parts.append("I03 vector-manifest")
    if args.validation:
        desc_parts.append("I04 validation-contract")
    desc = ", ".join(desc_parts)

    print(f"PASS: {checked} satisfy the {desc} invariants "
          f"({version_info}, {total_checks} check groups)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
