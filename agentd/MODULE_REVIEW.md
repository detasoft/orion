# Module Review: `agentd`

## 3. Bundled installation creates state that daemon startup rejects

**Problem.** Direct `AgentdMain` startup with an absent `--state-dir`, no `--session-host` override, and a
POSIX umask such as `022` installs the bundled host before acquiring the process lock. Default directory
permissions expose state to other user classes; the lock then rejects that directory and startup fails.
`LocalAgentMain` already creates private state, so `make run-agent` is protected.

**Sources.** [Parsing and install](src/main/java/pro/deta/orion/agentd/core/AgentConfiguration.java#L80),
[default directory creation](src/main/java/pro/deta/orion/agentd/runtime/BundledSessionHost.java#L91),
[state rejection](src/main/java/pro/deta/orion/agentd/core/AgentProcessLock.java#L45),
[daemon caller](src/main/java/pro/deta/orion/agentd/AgentdMain.java#L65), and
[assembly](src/main/java/pro/deta/orion/agentd/core/Agent.java#L61).
[ConfigurationTest](src/test/java/pro/deta/orion/agentd/core/AgentConfigurationTest.java#L93) checks absent-state
installation but not subsequent locking; [ProcessLockTest](src/test/java/pro/deta/orion/agentd/core/AgentProcessLockTest.java#L90)
separately checks shared-directory rejection. [Local launcher](src/main/java/pro/deta/orion/agentd/LocalAgentMain.java#L53)
prepares private state.

**Documented behavior.** [README](README.md#L17) promises owner-only state and
[documents bundled installation](README.md#L159).

**Contract.** Fresh ordinary daemon startup must create usable private state; unsafe existing state remains
rejected, and the executable override remains supported.

**Minimal repair.** Create missing state/runtime directories with owner-only POSIX attributes through the
existing directory policy. Cover bundled installation followed by lock acquisition, and existing shared-state
rejection.

**Alternatives and consequences.** Moving installation after lifecycle locking broadens ownership changes.
Weakening lock rejection breaks privacy. Requiring manual private-directory creation narrows automatic startup.
No wire or persisted-contract change is required.

**Confidence.** High from the composed production path under permissive POSIX umask; no runtime reproduction run.

**Priority signals.** Importance: high for fresh direct daemon startup. Repair ease: high to medium, local to
existing directory handling.

## 4. Reader tests assert incidental Java structure through reflection

**Problem.** Behavior-preserving changes to journal-position visibility or manifest representation fail
reflection assertions requiring particular methods, constructor modifiers, or absent record components.

**Sources.** [Journal reader test](src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java#L132)
enumerates public methods and constructors of
[JournalReadPosition](src/main/java/pro/deta/orion/agentd/journal/JournalReadPosition.java#L8).
[Manifest reader test](src/test/java/pro/deta/orion/agentd/session/JsonSessionManifestReaderTest.java#L39)
enumerates components of [SessionManifest](src/main/java/pro/deta/orion/agentd/session/SessionManifest.java#L7)
to assert removed fields are absent. Existing page and
[manifest-reading behavior](src/main/java/pro/deta/orion/agentd/session/JsonSessionManifestReader.java#L42)
already exercise actual consumers, including ignored metadata fields.

**Documented behavior.** The [test-quality rule](../.agents/skills/orion-minimal-implementation/SKILL.md#verify-and-review)
prohibits incidental structure assertions. No reflective consumer contract was found.

**Contract.** Preserve page limits, resumption and rotation, validation, metadata interpretation, and ignored-field
behavior. Reflection itself is not a required contract.

**Minimal repair.** Delete the two reflection assertion blocks and newly unused imports; retain their behavioral
assertions and surrounding tests.

**Alternatives and consequences.** Source inspection retains the violation. New public test APIs add unnecessary
surface. Deleting these assertions changes no production, wire, or persistence behavior.

**Confidence.** High; both blocks directly match the prohibited pattern.

**Priority signals.** Importance: medium as an explicit repository review violation. Repair ease: very high,
a behavior-preserving deletion.

## 5. Server lanes preserve receipts while terminal effects can reorder

**Problem.** A native handler sends the first input admission receipt, then pauses before acquiring the effect
mutex. AgentD advances its server lane and a later input chunk or resize on another connection acquires the
mutex first. Input bytes and resize effects can reach the child in a different order.

**Sources.** [Authenticated control dispatch](src/main/java/pro/deta/orion/agentd/core/AgentControlService.java#L281),
[lane drain](src/main/java/pro/deta/orion/agentd/core/SessionCommandLanes.java#L90),
[separate claim/command sends](src/main/java/pro/deta/orion/agentd/session/EstablishedSessionCommandDelivery.java#L47),
and [fresh sockets](src/main/java/pro/deta/orion/agentd/session/UnixDomainControlTransport.java#L18).
Native [receipts precede locking](../session-host/src/platform/unix.rs#L1297) and
[connections run independently](../session-host/src/platform/unix.rs#L1049).
The [browser terminal](../net/frontend/ui/src/components/SessionTerminal.vue#L31) splits input into 8192-byte
commands; [its drain](../net/frontend/ui/src/lib/session-commands.js#L54) advances after submission while
confirmation polling proceeds separately.
[DeliveryTest](src/test/java/pro/deta/orion/agentd/session/EstablishedSessionCommandDeliveryTest.java#L32)
checks fake delivery, without competing native effects.

**Documented behavior.** [AgentD acceptance](../docs/plans/tasks/04_agentd/08_release-and-acceptance.md#L14)
requires ordered terminal events. A terminal input stream must preserve byte order across chunks.
The [native protocol](../session-host/protocol/README.md#L244) explicitly provides admission before effects and
no FIFO across connections; that weaker native contract is not itself a defect.

**Contract.** Preserve terminal byte/effect order, no replay after ambiguous delivery, authenticated ownership
and fencing, operation deadlines, and termination while ordinary input is blocked.

**Minimal repair.** Reuse [SessionControlClient.Connection](src/main/java/pro/deta/orion/agentd/session/SessionControlClient.java#L47)
as a lane-owned ordinary-command connection. Define disconnect, failure, fencing, and shutdown ownership;
preserve a termination path that bypasses blocked input. Cover delayed earlier input, multiple chunks,
intervening resize, and termination through the real server path.

**Alternatives and consequences.** Waiting for matching journal results adds cross-channel coordination and
latency. Global native FIFO broadens its contract. Persistent connections reuse an existing mechanism but
require lifecycle verification; blindly routing termination through the ordinary socket can make it unavailable.
No replay fallback is justified.

**Confidence.** High for the scheduling interleaving and real consumer dependence; frequency was not measured.

**Priority signals.** Importance: high, due to reordered command bytes. Repair ease: medium to low, because
connection lifecycle and termination require coordinated checks.
