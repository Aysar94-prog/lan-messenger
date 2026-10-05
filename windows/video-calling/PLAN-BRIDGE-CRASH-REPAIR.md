# Windows native bridge crash / empty-offer repair — 2026-10-05

User authorized takeover and repair. Windows only; preserve artifacts and user edits.
Production factory/video activation and release/device acceptance remain out of scope.

Execution checkpoint: CR-01 through CR-04 complete. Original dump captured; explicit codec
dependencies and bounded SDP waits repaired. CR-05: ABI 39/39, readiness 24/24,
offer/answer/teardown cycles 20/20, Release 0 errors; full regression running.
CR-06: evidence and status updated, local commit pending. Historical Windows master-plan
file contains invalid UTF-8 and was not re-encoded; the repair report supersedes its old
WVC-05 no-ADM/undriven-socket/21-check notes. WVC-06/T02 remain Partial.

| Task | Status | Dependencies | Notes | Acceptance/testing |
|---|---|---|---|---|
| CR-01 inventory | Done | None | Original artifacts preserved; transient source diff unavailable. | Reproduced without rebuilding originals. |
| CR-02 crash evidence | Done | 01 | Portable ProcDump/cdb; no WER registry change. | Private full dump and null-dereference instruction during factory construction. |
| CR-03 isolate causes | Done | 01,02 | Missing codec factories; premature asynchronous SDP read and absent audio transceiver. | Upstream constructor/source and failing old-baseline isolate. |
| CR-04 implementation | Done | 03 | Explicit codecs/dummy ADM, bounded waits, driven socket/runtime lifecycle. | Unique output with source/script snapshots, manifest, PDB/map. |
| CR-05 verify | In progress | 04 | ABI 39/39, readiness 24/24, audio cycles 20/20, call-video 317/0, Release 0 errors. | Full regression running; no physical acceptance inferred. |
| CR-06 handoff | In progress | 05 | Evidence/status updated, local commit pending. | WVC-06/T02 Partial; no push/release. |
