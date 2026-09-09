# Orion task streams and execution order

Analysis date: 2026-09-09. Baseline: `b42b400e` on `main`.

This is the sequencing map for [the task tree](TASK.md), not a second status
register. Task nodes retain their paths, scope, and ownership. Numbers below
are the recommended implementation order within each stream. Explicit gates
are prerequisites; other ordering reduces overlapping edits and rework.
Independent streams can advance together.

## Selection and coverage

Next overall: [remaining key-owner migration](current-work/unified-key-material-bootstrap/remaining-key-owner-migration/TASK.md).
Typed capabilities and server identity are integrated. ACME migration is also
integrated, but [SshHostKeyService](../../core/common/src/main/java/pro/deta/orion/crypto/SshHostKeyService.java)
still creates and loads
`ssh-host-keys/rsa.pem` and `ecdsa.pem`. Start by checking the remaining
consumers, then finish the single material-store path without rebuilding ACME.

Next in the independent Agent control stream:
[agent and launch records](current-work/agent-session-server/control-and-registries/agent-and-launch-records/TASK.md).
Agent identity and HTTP/2 transport exist; durable registration is the next
server dependency. AgentD handshake can be developed against the existing
protocol in parallel.

Before choosing another task:

1. Recheck its node and ancestors for ownership, and inspect current changes.
2. Require prerequisites to be integrated; a branch or missing task directory
   alone is not evidence of completion.
3. Prefer ready current-work deletion/consolidation tasks. Then follow the
   current-work stream priority; use upcoming work for required prerequisites
   or after the current ready set is exhausted.
4. Check the coordination gates below before editing shared consumers. Do not
   take over occupied work without explicit authorization.

Coverage: all 102 existing `TASK.md` nodes (83 leaves), their dependency declarations,
current worktrees, and relevant integration history. Spot checks included
server journal storage and module dependencies, AgentD handshake/control and
native consumers, SSH key ownership, and HTTP material consumers. This is a
task/dependency audit, not an exhaustive implementation or correctness review.
Readiness means no known planning blocker; tests were not run for this audit.

The smallest change is to group existing queue links and record leaf order
here. No stream directories, new task identities, scheduler, source changes,
or ownership changes are needed.

## 1. Material and configuration

Two branches can advance independently: material ownership, and ACL/configuration.
Coordinate edits to bootstrap, schema, configuration mutation, and terminal
administration with their active owners.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| M1 | [Remaining key owners](current-work/unified-key-material-bootstrap/remaining-key-owner-migration/TASK.md) | **Next overall**; remove the remaining independent key storage. |
| M2 | [JWT rotation and refresh](current-work/unified-key-material-bootstrap/short-lived-jwt-rotation-and-refresh/TASK.md) | Identity foundation is integrated; may run beside M1 if consumers do not overlap. |
| M3 | [Rotation/recovery acceptance](current-work/unified-key-material-bootstrap/rotation-recovery-and-acceptance/TASK.md) | M1 + M2; encrypted configuration foundation is integrated. |
| C1 | [Local ACL characterization](upcoming-work/acl-storage-hardening/local-storage-characterization/TASK.md) | Capture retained behavior before changing storage contracts. |
| C1a | [Unused ACL helpers](upcoming-work/acl-storage-hardening/remove-unused-helpers/TASK.md) | Independent deletion if the unsupported path and all real callers are gone; recheck, do not manufacture work. |
| C2 | [Canonical repository names](upcoming-work/acl-storage-hardening/canonical-repository-names/TASK.md) | Coordinate with Git parser/storage boundary; one storage-neutral identity. |
| C3 | [Local path containment](upcoming-work/acl-storage-hardening/local-path-containment/TASK.md) | After characterization and canonical path decisions. |
| C4 | [Exact snapshot save](upcoming-work/acl-storage-hardening/exact-snapshot-save/TASK.md) | Preserve the established path boundary and publication semantics. |
| C5 | [Consistent storage failures](upcoming-work/acl-storage-hardening/consistent-storage-failures/TASK.md) | Migrate the settled read/write contract once. |
| C6 | [Hierarchical authorization](current-work/hierarchical-orion-configuration/hierarchical-authorization/TASK.md) | ACL foundation first, as required by its parent; users/roles are integrated. |
| C7 | [Native Git configuration snapshots](current-work/hierarchical-orion-configuration/native-git-configuration-snapshots/TASK.md) | Can advance beside C1–C6 using the completed material/configuration barrier; coordinate bootstrap ownership. |
| C8 | [Configuration administration/acceptance](current-work/hierarchical-orion-configuration/administration-and-acceptance/TASK.md) | C6 + C7 and integrated repository/mirror configuration. |

## 2. Agent control, replication, and commands

The server owns durable agent/launch identity and journal cursors. AgentD owns
its outbound connection and discovers independent session hosts. Implement the
control foundation before attaching replication and command routing to it.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| A1 | [Agent/launch records](current-work/agent-session-server/control-and-registries/agent-and-launch-records/TASK.md) | **Next server task**, available. |
| A2 | [Server launch/reconnect authentication](current-work/agent-session-server/control-and-registries/launch-and-reconnect-authentication/TASK.md) | A1; HTTP/2 transport is integrated. |
| A3 | [Connection ownership/health](current-work/agent-session-server/control-and-registries/connection-ownership-and-health/TASK.md) | A2. |
| A4 | [Session reconciliation/ownership](current-work/agent-session-server/control-and-registries/session-reconciliation-and-ownership/TASK.md) | A3. |
| A5 | [Server runtime/provisioning integration](current-work/agent-session-server/control-and-registries/runtime-and-provisioning-integration/TASK.md) | A1–A4; use current AgentD and a live protocol peer. |
| D1 | [AgentD handshake/authentication](current-work/agentd/control-connection-lifecycle/handshake-and-authentication/TASK.md) | Available beside A1; agree authentication behavior with A2. |
| D2a | [Reconnect/heartbeat](current-work/agentd/control-connection-lifecycle/reconnect-and-heartbeat/TASK.md) | D1; counterpart A3. |
| D2b | [Discovery/reporting](current-work/agentd/control-connection-lifecycle/session-discovery-and-reporting/TASK.md) | D1; independent of D2a; counterpart A4. |
| D3 | [AgentD control runtime/acceptance](current-work/agentd/control-connection-lifecycle/runtime-and-acceptance/TASK.md) | D2a + D2b + A5. A5 does not depend on D3. |
| J1 | [Server session replication](current-work/agent-session-server/session-replication/TASK.md) | A5; durable journal storage is integrated. The old worktree does not make this ready. |
| J2 | [AgentD journal sync](current-work/agentd/journal-sync/TASK.md) | D3 + J1 for integrated delivery; settle ACK sequence allocation with command orchestration. |
| J3 | [Remaining native contract alignment](current-work/agentd/session-host-contract-alignment/TASK.md) | Audit against integrated focused fixes before coding; finish with J2 and K2. Do not make the whole audit a prerequisite of those implementations. |
| K1 | [Server command service](current-work/agent-session-server/command-service/TASK.md) | A5; recommend J1 first for durable command evidence. Agree retry/unknown-result semantics with K2. |
| K2 | [AgentD command orchestration](current-work/agentd/command-orchestration/TASK.md) | **Paused/owned**; journal sync and current native start/control contracts first. Resolve shared sequence allocation with J2 before either is closed. |
| K3 | [Diagnostic secret redaction](current-work/agentd/diagnostic-secret-redaction/TASK.md) | K2; required before AgentD release. |
| K4 | [Platform status/resilience](current-work/agentd/platform-status-and-resilience/TASK.md) | D3 + J2 + K2. |
| E1 | [Historical/live event API](current-work/agent-session-server/live-event-api/TASK.md) | J1 and integrated storage; can advance beside command routing. |

J2, J3, and K2 share a contract decision, not a requirement to wait for one
another's entire task to finish. Agree one SERVER sequence allocator/recovery
rule for ACK and commands with K2's owner; implement each existing consumer in
its task. Unknown or missing journal results must not authorize effect replay.

The [current native contract](2026-09-03-native-control-journal-idempotency-design.md)
explicitly removes durable intents and treats missing results as unknown.
The server command task still describes retry/deduplication more strongly than
the native high-water-mark contract, and K2 still mentions intent records.
Reconcile these descriptions with the current native contract before their
implementation; do not build a second durable command ledger to satisfy stale
wording. This audit does not rewrite the occupied task.

## 3. Native session execution

Process and PTY control work is occupied by `native-process-control-47c2`.
Children of that occupied parent are also unavailable for a new generic claim.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| N1 | [Linux process-tree control](current-work/native-session-host/linux-process-tree-control/TASK.md) | **Owned**; platform process identity and discovery boundary. |
| N1a | [Linux proc access failures](current-work/native-session-host/linux-proc-discovery-errors/TASK.md) | Reproduce first; coordinate the same discovery code with N1. Recheck after N1 lands. |
| N2a | [Process listing/addressed signals](current-work/native-session-host/process-control-and-pty-closure/list-processes/TASK.md) | **Parent owned**; use N1's identity contract. |
| N2b | [PTY closure](current-work/native-session-host/process-control-and-pty-closure/pty-closed/TASK.md) | **Parent owned**; common journal/control contract, separate from process liveness. |
| N3 | [Explicit termination](current-work/native-session-host/termination-coordination/TASK.md) | N2a + N2b; keep policy outside the host. |
| N4 | [Shutdown hardening](current-work/native-session-host/termination-shutdown-hardening/TASK.md) | N3 + N2; recommend before the final lifecycle simplification. |
| N5 | [Lifecycle ownership simplification](current-work/native-session-host/simplify-session-lifecycle/TASK.md) | N2 + N3; audit remaining duplication after N4, do not reimplement PTY closure. |
| N6 | [Source-aware controls](current-work/native-session-host/source-aware-controls/TASK.md) | Native-control baseline is integrated. Coordinate protocol/Java edits with N2 and K2; settle reconnect allocation first. |
| N7 | [Harness event ingress](current-work/native-session-host/harness-events/TASK.md) | Existing journal/Unix baseline suffices; recommended after shared control changes to avoid competing protocol edits. |
| N8 | [Windows host](current-work/native-session-host/windows-host/TASK.md) | Bootstrap work can start independently; finish parity against the settled common control/PTY/source contracts. |
| N9 | [Native packaging/acceptance](current-work/native-session-host/release-and-acceptance/TASK.md) | All required native siblings, including Windows; do not silently narrow the release matrix. |

N3, N4, and N5 overlap in blocked input, termination, and lifecycle ownership.
Their distinct outcomes are entry/signal delivery, finalization, and removal of
remaining duplicate state. At each step, inspect the preceding result and
close already-satisfied scope with evidence instead of adding another coordinator.

## 4. Git protocol, client, storage, and transports

Wire and client changes are separate branches with shared-consumer coordination.
Review storage after its boundary stabilizes, before externalizing it.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| G1 | [Canonical Git object ID](upcoming-work/git-wire-architecture-simplification/canonical-git-object-id/TASK.md) | First wire task. |
| G2 | [Blocking output migration](upcoming-work/git-wire-architecture-simplification/blocking-output-migration/TASK.md) | Follow G1; delete resumable output machinery. |
| G3 | [Parser/storage boundary](upcoming-work/git-wire-architecture-simplification/parser-storage-boundary/TASK.md) | G2; coordinate canonical repository names C2. |
| G4 | [Repository command context](upcoming-work/git-wire-architecture-simplification/repository-command-context/TASK.md) | G3; resolve repository resources once. |
| G5 | [Capability advertisement policy](upcoming-work/git-wire-architecture-simplification/global-capability-advertisement-policy/TASK.md) | G4; one veto at settled composition points. |
| G6 | [Native storage architecture review](upcoming-work/git-native-storage-architecture-review/TASK.md) | G1 + G3 + G4; can run beside G5. |
| G7 | [Wire re-audit](upcoming-work/git-wire-architecture-simplification/post-simplification-review/TASK.md) | G1–G6; reuse the storage review. |
| L1 | [Single-session request planning](upcoming-work/git-client-architecture-simplification/single-session-request-planning/TASK.md) | First client task; coordinate proxy/upstream consumers with their owners. |
| L2 | [Phase-aware exchange](upcoming-work/git-client-architecture-simplification/phase-aware-transport-exchange/TASK.md) | L1. |
| L3a | [Factual failure model](upcoming-work/git-client-architecture-simplification/factual-failure-model/TASK.md) | L2. |
| L3b | [Session inactivity timeout](upcoming-work/git-client-architecture-simplification/session-inactivity-timeouts/TASK.md) | L2; can run beside L3a with shared-file coordination. |
| L4 | [Blocking report-status parser](upcoming-work/git-report-status-blocking-parser/TASK.md) | Recommended after L2/L3 and before parity/re-audit; removes a second parsing path. |
| L5 | [Smart HTTP/SSH parity](upcoming-work/git-client-architecture-simplification/smart-http-ssh-behavior/TASK.md) | L1–L3 required; include L4's completed parser behavior. |
| L6 | [Client removal re-audit](upcoming-work/git-client-architecture-simplification/post-simplification-removal-review/TASK.md) | L1–L5 + G3. |
| G8 | [Server transport review](upcoming-work/git-server-transport-architecture-review/TASK.md) | G7 + L6 + completed remote proxy bootstrap R1–R4. |
| G9 | [Virtual-thread SSH/Jetty transport](upcoming-work/virtual-thread-jetty-ssh-git-transport/TASK.md) | G8; finish HTTP hardening H1–H2 before changing Jetty execution. |
| G10 | [External repository storage](upcoming-work/externalized-repository-storage/TASK.md) | G6 and its accepted prerequisite follow-ups; choose the concrete backend before extraction. Independent of G9. |

G9 and G10 currently contain implementation checklists rather than child task
directories. Treat each as one existing task until its reviewed design supports
a bounded split. Do not claim an untracked checklist item as a task node.

## 5. Remote Git bootstrap and synchronization

A bootstrap proxy and repository synchronization have different runtime duties,
but must consume the same canonical Git client/storage and secret mechanisms.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| R1 | [Bootstrap proxy runtime](current-work/remote-git-proxy-bootstrap/bootstrap-proxy-runtime/TASK.md) | **Owned**, and parent owned; integrate the existing work first. |
| R2 | [Proxy configuration/secrets](current-work/remote-git-proxy-bootstrap/proxy-config-and-secrets/TASK.md) | R1; typed material/encryption foundations are integrated; parent remains owned. |
| R3 | [Proxy admin UI](current-work/remote-git-proxy-bootstrap/proxy-admin-ui/TASK.md) | R2; coordinate terminal administration and configuration mutation. |
| R4 | [Native proxy integration migration](current-work/remote-git-proxy-bootstrap/native-integration-test-migration/TASK.md) | R1–R3. |
| R5 | [Git configuration fixture repair](upcoming-work/git-configuration-integration-fixtures/TASK.md) | Reproduce/check after R4; retain only unresolved fixture work, no second proxy implementation. |
| S1 | [Primary upstream synchronization](current-work/external-git-repository-sync/primary-upstream/TASK.md) | **Paused/owned**; foundation is integrated, implementation remains on its branch. |
| S2 | [Branch filtering](current-work/external-git-repository-sync/branch-filtering/TASK.md) | S1 integrated first. |
| S3 | [GitHub commit replication](upcoming-work/github-commit-replication/TASK.md) | Reconcile the older plan with S1/S2 and the parent GitHub checklist before starting. |

The external-sync parent also lists SSH credentials/host verification, GitHub
App credentials/webhooks, and secondary remotes/tags without child nodes.
Recommended extension order after S1: branch filtering, SSH credentials,
GitHub App/webhooks using S3's agreed scope, then secondary remotes/tags.
Create bounded child tasks only when taking up those extensions, with the
required immediate task-creation commit. Do not build a separate GitHub sync
engine or duplicate the App/webhook task in two places.

## 6. Operator interfaces and HTTP

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| H1 | [Unified HTTP invocation](upcoming-work/http-core-hardening/unified-route-invocation/TASK.md) | Available foundation; coordinate route consumers under active administration work. |
| H2 | [Typed route matching](upcoming-work/http-core-hardening/typed-route-matching/TASK.md) | H1. |
| T1 | [Terminal administration](current-work/interactive-ssh-shell/terminal-administration/TASK.md) | **Owned**; integrate terminal interaction/configuration work. |
| T2 | [SSH PTY timeout](upcoming-work/ssh-pty-completion-timeout/TASK.md) | Recheck after T1; fix only a remaining reproducible failure. |
| T3 | [Streaming monitoring](current-work/interactive-ssh-shell/streaming-monitoring/TASK.md) | Core/terminal baseline is integrated; recommend T1/T2 first because rendering and cancellation overlap. |
| T4 | [AgentD local terminal](current-work/agentd/local-terminal/TASK.md) | J2 + K2 + N6; offline manual mode still uses the canonical control/journal path. |
| T5a | [Web terminal](current-work/agent-session-server/web-terminal/TASK.md) | K1 + E1; end-to-end verification also needs K2/J2. |
| T5b | [SSH session PTY gateway](current-work/interactive-ssh-shell/session-host-pty-gateway/TASK.md) | Server control + K1 + E1; terminal foundation integrated. Can advance beside T5a. |
| T6 | [SSH security/acceptance](current-work/interactive-ssh-shell/security-and-acceptance/TASK.md) | Required shell siblings T1 + T3 + T5b, with T2 resolved or shown obsolete. |

## 7. Release, deployment, and final reviews

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| P1 | [AgentD packaging/MVP](current-work/agentd/release-and-acceptance/TASK.md) | All AgentD siblings, T4, and native packaging N9. |
| P2 | [Server MVP acceptance](current-work/agent-session-server/release-and-acceptance/TASK.md) | Server siblings through T5a; shared scenario requires working AgentD commands/replication. Can share acceptance evidence with P1. |
| P3 | [Remote provisioning administration/acceptance](current-work/remote-machine-provisioning/administration-and-end-to-end-acceptance/TASK.md) | Packaged AgentD/native host, A5, protected configuration, and existing SSH enrollment/recovery. No separate provisioning registry. |
| P4 | [Agent control-plane review](upcoming-work/agent-session-control-plane-architecture-review/TASK.md) | P1 + P2. |
| P5 | [Runtime composition review](upcoming-work/runtime-composition-root-architecture-review/TASK.md) | C8 + M3 + G8. It need not wait for unrelated platform work or G9/G10. |

P1/P2 are acceptance joins, not mutual implementation dependencies. P3 consumes
packaged/runtime results; A5 uses existing provisioning interfaces and therefore
does not wait for P3.

## 8. Maintenance and optional platform work

| Existing task | Disposition |
| --- | --- |
| [Stale branches/worktrees](current-work/stale-branches-and-worktrees/TASK.md) | **Paused/owned**; preserve unique work and regression coverage. Not a global prerequisite. |
| [Stalled-operation watchdog](current-work/remote-machine-provisioning/stalled-operation-watchdog-baseline/TASK.md) | Already complete; exclude from next-task selection. |
| [macOS process inspection](upcoming-work/macos-agentd-process-inspector/TASK.md) | Decide cooperative-only versus forced recovery before designing a helper. Not a Linux production-release gate. |
| [Control request queue](upcoming-work/session-host-control-request-queue/TASK.md) | Explicitly deferred; require observed need, compare a simple admission limit first. |

## Completed foundations and stale references

Thirteen distinct missing task targets remain in existing nodes. They point
to integrated foundations below; two provisioning references additionally omit
the parent traversal. Keep this evidence when reading those dependencies.
Missing files by themselves do not establish completion.

| Removed task/foundation | Integrated evidence |
| --- | --- |
| Typed material capabilities | `f540dd1e` |
| Configuration secret cryptography | `d17476ab` |
| Server identity migration | `6679da3b` |
| Organization users and scoped roles/grants (two nodes) | `748088a4` |
| Repository/mirror configuration | `146b9e76` |
| AgentD journal reader | `02e74a3a` |
| AgentD native-control contract | `c298ad34`; termination-mode verification `2f10c263` |
| Server journal storage | `f8a413ba`; production `SessionJournalStorage` and filesystem implementation remain present. |
| SSH command core | `3ed68b68` |
| Interactive terminal foundation | `1f99a9ea`; does not close the later occupied administration task. |
| SSH key enrollment | `0d360af8` |
| Agent replacement/recovery | `f239010f` |

ACME key migration (`d1034fe0`) is completed scope inside M1, not evidence that
all key owners are migrated. HTTP/2 control transport (`93f31867`) is complete,
but authentication, durable records, and connection ownership remain tasks.

## Ownership and coordination observations

Owner markers remain authoritative even if a corresponding worktree is absent.
No occupied task was claimed, edited, released, or marked complete in this audit.

At inspection, dedicated worktrees existed for command orchestration,
interactive terminal work, Linux process control, primary upstream sync, and
session replication. The session-replication node was explicitly released by
`79c0d99f`; its stale worktree is neither proof of current ownership nor an
implemented prerequisite. Inspect/reconcile it before reuse without deleting
unique work. Proxy parent/runtime owners remain recorded even though no proxy
worktree appears in the current list.

Pre-existing changes in `Makefile`, `README.md`, `core/schema/pom.xml`, and
`OrionXmlSchema.java` are outside this planning change. Recheck their ownership
before starting work that edits the same build/schema surfaces.

The main simplification findings are scheduling-level: duplicated GitHub scope,
overlapping native lifecycle closure, integration repairs overlapping occupied
feature work, and stale references that hide completed prerequisites. Preserve
the real boundaries: host process ownership, server-durable journal authority,
single material ownership, and authentication before server work is admitted.
