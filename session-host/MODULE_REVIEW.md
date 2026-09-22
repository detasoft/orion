# Module Review: `session-host`

### 1. Detached connection workers form an unbounded pending-operation queue

**Problem.** If a child stops consuming PTY input, successive commands can still receive `RECEIVED` while their
workers accumulate behind the blocked effect. AgentD's bounded manual queue drains on each receipt and opens
another native connection; closing the old socket does not cancel its admitted operation. Idle clients also
retain detached workers without a read deadline or connection bound.

**Sources.** [`spawn_accept_loop`](src/platform/unix.rs#L1029) creates a detached thread per socket;
[`serve_connection`](src/platform/unix.rs#L1081) reads the next frame without a read deadline.
[`handle_operation`](src/platform/unix.rs#L1290) acknowledges before acquiring the ordinary-effect mutex;
[PTY writes](src/platform/unix.rs#L1441) can remain blocked. The real
[terminal lane](../agentd/src/main/java/pro/deta/orion/agentd/terminal/LocalTerminalAttacher.java#L328)
advances on receipt, and its [transport](../agentd/src/main/java/pro/deta/orion/agentd/session/UnixDomainControlTransport.java#L19)
opens a fresh connection. The [blocked-input test](tests/unix_process_host.rs#L1567) preserves independent
admission but does not bound accumulated workers or operations.

**Documented behavior.** The [native protocol](protocol/README.md#L254) intentionally separates admission from
effects and lets `TERMINATE` bypass the effect mutex. The
[queue investigation](../docs/plans/tasks/05_native-session-host/12_control-request-queue.md) is explicitly
deferred and does not establish a client count or authorize a particular queue.

**Contract.** Preserve nonblocking admission where capacity exists, execution uncertainty after disconnect,
and a usable termination path during blocked input. Maximum clients, pending effects, and overload behavior
remain unspecified and require a decision before repair.

**Minimal repair.** Establish required concurrency, then bound connection/admission resources using the existing
blocking implementation. Prevent ordinary blocked work from consuming all termination capacity. Cover overload,
idle clients, blocked input, and finalization; a new pool or async runtime is not justified by present evidence.

**Alternatives and consequences.** A persistent AgentD manual connection reduces the demonstrated terminal
trigger and helps ordering, but does not bound other clients. A naive connection cap can itself block termination.
Rejecting excess work changes overload behavior, which must be explicit.

**Confidence.** High in the mechanism and real consumer. The required capacity and production exhaustion
frequency are unknown; no stress reproduction was run.

**Priority signals.** Importance: medium, with potentially high impact under sustained blocked input.
Repair ease: medium to low because resource bounds and termination availability must be designed together.

### 6. Accepted segment targets can create compressed journals that readers reject

**Problem.** `--journal-segment-bytes 1073741824 --journal-max-bytes 2147483648` is accepted. After a segment grows
beyond 512 MiB and closes, maintenance compresses it, but native full scans and AgentD's compressed reader reject
decoded data beyond 512 MiB. Ordinary compression can therefore make valid writer output unreadable to replay,
replication, and retention scanning. Default 64 MiB targets are unaffected.

**Sources.** [CLI validation](src/cli.rs#L104) and [`validate_config`](src/journal.rs#L751) check positivity and
total-versus-segment size without an upper target bound. [Rotation](src/journal.rs#L393) follows that target,
and [`compress_segment`](src/journal.rs#L727) compresses the entire file. Native
[`scan_path`](src/journal.rs#L1097) enforces `MAX_DECOMPRESSED_SEGMENT_LENGTH` for full compressed scans;
the [AgentD reader](../agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java#L391)
enforces the same 512 MiB cap. The [configuration test](src/journal.rs#L2487) covers zero and insufficient total
size, not this writer/reader mismatch.

**Documented behavior.** [Storage configuration](README.md#L104) documents positive byte counts and
`maximum >= segment`. The [compression contract](protocol/README.md#L521) requires the same logical records
after decompression; it does not advertise an accepted configuration that becomes unreadable after rotation.

**Contract.** Accepted writer settings must remain consumable after compression while preserving bounded
reading, indivisible records, durable acknowledgement, and the active-segment retention boundary.

**Minimal repair.** Reject incompatible segment targets before launch and at the existing writer configuration
boundary, using the established reader maximum. Document the supported range and test its boundary together
with whole-record rotation. Individual record limits already lie far below 512 MiB.

**Alternatives and consequences.** Supporting larger segments instead requires deliberately revisiting both
native and AgentD reader bounds and their resource containment. Validation narrows previously accepted custom
settings but changes neither defaults nor wire/persisted representations; it does not repair existing oversized
files, whose handling needs an explicit compatibility decision if such files exist.

**Confidence.** High, a deterministic configuration/reader mismatch. No large-file reproduction was executed.

**Priority signals.** Importance: medium, custom settings can stop journal consumption and retention.
Repair ease: high to medium for validation; broader large-segment support is a larger cross-module change.
