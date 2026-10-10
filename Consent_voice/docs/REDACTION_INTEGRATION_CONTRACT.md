# Redaction Integration Contract

Contract version: 2. Rahul owns the input decisions. Yazeen owns the redaction engine implementation in the separate `Voice_redaction` workspace.

## Input

`RedactionRequest` contains a private audio reference, format metadata, and a list of `SpeechDecision` items:

| Field | Meaning |
| --- | --- |
| `startMs`, `endMs` | Original audio timeline; `0 <= startMs < endMs <= durationMs` |
| `state` | `PROTECTED`, `UNCERTAIN`, or `OVERLAP` |
| `score` | Rahul's speaker-match score, used only for audit/UI |
| `profileId` | Approved protected-profile identifier |

Version 2 may also contain `ReplacementAudioSegment` items. A segment has an
original-timeline `startMs`/`endMs`, exactly time-aligned 16 kHz mono PCM, and
the source marker `SEPARATED_UNPROTECTED`. It replaces the original mixed
samples for its whole interval; it is never mixed with them.

## Required behavior

1. Reject a request with an incompatible contract version or invalid timeline range.
2. Sort and merge touching or overlapping ranges.
3. Mute every `PROTECTED`, `UNCERTAIN`, and `OVERLAP` range with short fades to avoid clicks, except where a validated v2 replacement completely covers an `OVERLAP` range.
4. Reject an invalid, non-finite, length-mismatched, or overlapping replacement and mute its full overlap range instead.
5. Write a new sanitized file; never overwrite or share the original input.
6. Return the sanitized private reference, applied ranges, warnings, and engine version.
7. Return failure rather than an apparently successful protected export if processing or file writing fails.

## Boundary

The redaction engine does not perform BLE, enrolment, VAD, speaker matching, or a second speaker model. It accepts decisions and redacts audio deterministically.
