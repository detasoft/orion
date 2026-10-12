# Recover ACL Configuration from Previous Revisions

Status: todo

## Requirements

When ACL cannot activate the current configuration, let it ask
`OrionConfigurationEditor` to find a usable previous revision. This replaces
the removed bootstrap snapshot fallback with explicit recovery from storage
history, including after a process restart.

- Keep XML decoding and Git details behind the configuration storage boundary.
- Validate historical documents through the same Editor path as current
  documents, including secret and key-material references.
- Preserve an already accepted runtime snapshot when an update is rejected.
- Distinguish an invalid document from unavailable storage; an infrastructure
  failure must not silently cause activation of older authorization data.
- Report both the rejected revision and the selected recovery revision.
- Fail startup clearly if no acceptable historical configuration is available.

## Design Decisions

Before implementation, settle whether recovery only activates an older snapshot
or also persists a corrective commit. Do not silently reset or force-update the
configuration ref. If persistence is required, use Editor modification access
and compare the expected revision before applying the corrective document.

Define the ancestry traversal, search limit, and missing-file/ref behavior.
Search only the selected configuration history, with an explicit policy for
merge commits. Account for older ACL revisions restoring users, credentials,
or permissions that were subsequently revoked; document when automatic
recovery is allowed.

ACL requests recovery from Editor. Editor coordinates history lookup and
validation; storage supplies typed revisions and pinned documents. Reuse the
existing revision and modification contracts rather than restoring
`initialConfiguration` or adding a second configuration loader.

## Implementation Plan

1. Trace existing revision reads and Git parent traversal, and define the
   smallest missing storage contract for historical lookup.
2. Implement recovery in Editor using canonical document validation, bounded
   ancestry traversal, and explicit recovery outcomes.
3. Integrate the agreed startup and reload policy into ACL while preserving
   its last accepted snapshot and asynchronous event delivery.
4. Add behavior coverage for recovery, exhausted history, infrastructure
   failure, and a concurrent configuration update during recovery.

## Dependencies

The typed revision, storage, Editor modification, and asynchronous ACL update
contracts must be settled before implementing this task. Any missing Git
ancestry API belongs to this task's necessary storage work.

## Acceptance Criteria

- Invalid HEAD with a usable ancestor recovers according to the agreed policy
  without a bootstrap-supplied snapshot.
- Several invalid ancestors are handled within the configured search boundary;
  no valid ancestor produces a clear failure.
- Historical XML with unavailable referenced material is rejected.
- Storage unavailability is reported without unauthorized historical fallback.
- A concurrent update cannot be overwritten by recovery or silently replaced
  with a stale accepted snapshot.
- Diagnostics identify the rejected and recovered revisions; any persisted
  correction uses ordinary versioned publication and asynchronous notification.
