# AgentD Session Discovery Design

## Goal

Reconstruct AgentD's local session cache from durable session directories at
startup and after filesystem notifications are lost, without making the cache
an additional source of truth.

## Durable Boundaries

The `metadata` file is a manifest and small snapshot only. Discovery reads the
session identity, compatibility versions, launch coordinates, terminal
snapshot, sandbox description, and control endpoint. It ignores unknown JSON
fields and deliberately does not require or expose `journalId`,
`activeSegment`, `oldestAvailableTimestamp`, `latestTimestamp`, or persisted
`state`. This keeps discovery compatible with the approved metadata-manifest
change even while the checked-in fixture still contains the removed fields.

`operationSequence` is not manifest or discovery state. It is assigned by
AgentD's later control-delivery path and must never be inferred from metadata,
a journal timestamp, a CBOR `eventId`, a PID, or filesystem order. Discovery
ignores an unknown metadata field with that name.

The manifest reader uses Jackson Core's streaming API with strict duplicate
field detection. It bounds the input, numeric values, arrays, and strings and
checks types explicitly while skipping unknown values. The reader does not use
Jackson Databind or annotations, keeping the dependency suitable for AgentD's
minimal jlink packaging.

Journal discovery is intentionally shallow. A `JournalProbe` establishes only
whether a session has a present, readable journal representation. It neither
decodes records nor publishes a range or cursor. The future journal reader
owns segment decoding, retained ranges, legacy timestamp handling, and CBOR
Sequence event IDs.

Host and child PIDs and endpoint values in the manifest are coordinates, not
liveness evidence. Classification uses only an injected `HostProbe`
observation. This lets the later local-control implementation provide a real
STATUS exchange without duplicating its protocol or turning a PID lookup into
proof of host identity.

## Model and Reconciliation

`SessionManifest` is the validated forward-compatible snapshot. A manifest is
accepted only when required current fields are well formed, its session ID
matches the directory name, and its endpoint descriptor is safe for its
declared transport. It contains none of the excluded journal, lifecycle, or
control-sequence fields.

`LocalSession` combines one complete manifest with host and journal probe
observations. Its discovery classification is:

- `LIVE` when the host probe successfully contacts the host and the journal is
  present and readable;
- `LOST` when the probe reports that the host cannot be contacted;
- `DEGRADED` when probing fails or the journal is unreadable.

Incomplete directories and invalid manifests are reported as isolated
`DiscoveryIssue` values and are not published as `LocalSession` instances.
This distinction records the incomplete/degraded condition without exposing a
partially initialized operational session.

Every reconciliation lists the configured root without following directory
links, builds a complete immutable next snapshot off to the side, and then
atomically replaces the registry snapshot. One invalid or concurrently created
directory cannot prevent valid peers from being published. Removed directories
disappear on the next full scan. A new AgentD process reconstructs the same
cache without reading prior AgentD state.

## Notifications and Recovery

`SessionDiscoveryMonitor` owns one `WatchService` registration and one
long-lived worker. It performs an initial full reconciliation, treats all
create/delete/modify notifications only as wake-ups, and performs the same full
reconciliation after `OVERFLOW`. A timed `WatchService.poll` supplies the
periodic full scan that recovers missed notifications. Watch keys are reset;
an invalid key is re-registered when possible. Closing the monitor closes the
watch service and stops the worker without touching any session host.

Reconciliation exceptions are recorded and the monitor continues so a
transient root or notification failure does not permanently stop discovery.

## Verification

Tests cover empty and populated startup, cache reconstruction by a fresh
registry, ignored removed and unknown metadata fields (including
`operationSequence`), invalid metadata isolation, live/dead host
classification, unreadable journal isolation, concurrent incomplete directory
creation, atomic manifest replacement, removal/reload, periodic recovery from
a missed notification, and `OVERFLOW` causing a full reconciliation.
