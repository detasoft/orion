# Module Review: `agent-session-server`

## 4. Command queries still rescan complete journals under a shared monitor

**Problem.** Input, resize, signal, terminate, and command-status requests now retain at most one matching
event. They still decode and validate every segment of the session journal for each query. Long histories can
therefore delay these calls and other commands that share the command-service monitor. Repeated polling adds
read work proportional to the complete history.

**Sources.** [Admission](src/main/java/pro/deta/orion/agent/server/command/SessionCommandService.java#L137)
and [status](src/main/java/pro/deta/orion/agent/server/command/SessionCommandService.java#L187) use
[selected reading](src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java#L269), which scans the
whole snapshot for integrity after finding a match. Real callers include
[HTTP commands](../net/http-core/src/main/java/pro/deta/orion/transport/http/SessionCommandsRoute.java#L43),
[terminal input](../net/frontend/ui/src/components/SessionTerminal.vue#L67), and
[status polling](../net/frontend/ui/src/lib/session-commands.js#L32).

**Contract.** Durable journal evidence remains the completion authority; terminal events prevent new effects.
Full-history readers retain their existing snapshot and integrity guarantees. Selected reads must still reject
corruption anywhere in their captured snapshot, including after the first match.

**Minimal repair.** Decide whether command queries need a separately maintained summary or index that can
answer them without rescanning history. If so, define its durability, recovery, and integrity relationship to
the journal before reducing reads. Measure query latency under growing output before setting a target.

**Alternatives and consequences.** The current selection removes unbounded result retention without adding
durable state, but retains full-scan I/O. Moving reads outside the command monitor might reduce cross-command
delay; it requires a separate sequence and delivery ordering review. A cache or projection needs a cursor and
recovery owner and cannot silently weaken journal evidence.

**Confidence.** High for the remaining full scan and shared monitor; no latency measurement was run.

**Priority signals.** Importance: medium to high for long interactive sessions and frequent polling. Repair
ease: low until the bounded-I/O contract and recovery model are chosen.
