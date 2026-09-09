# Orion task dependency and coordination audit

Analysis date: 2026-09-09. Baseline: `b42b400e` on `main`.

This audit records dependency and coordination evidence for [the task tree](TASK.md).
Table labels identify tasks within this dated analysis; current selection order
comes only from the locally numbered filesystem entries. Explicit gates remain
prerequisites. Independent streams can advance together.

## Selection and coverage

At the audit baseline, the next overall candidate was
[remaining key-owner migration](current-work/01_unified-key-material-bootstrap/01_remaining-key-owner-migration.md).
Typed capabilities and server identity are integrated. ACME migration is also
integrated, but [SshHostKeyService](../../core/common/src/main/java/pro/deta/orion/crypto/SshHostKeyService.java)
still creates and loads
`ssh-host-keys/rsa.pem` and `ecdsa.pem`. Start by checking the remaining
consumers, then finish the single material-store path without rebuilding ACME.

The corresponding independent Agent control candidate was:
[agent and launch records](current-work/03_agent-session-server/01_control-and-registries/01_agent-and-launch-records.md).
Agent identity and HTTP/2 transport exist; durable registration is the next
server dependency. AgentD handshake can be developed against the existing
protocol in parallel.

Before choosing another task:

1. Recheck its node and ancestors for ownership, and inspect current changes.
2. Require prerequisites to be integrated; a branch or missing task file
   alone is not evidence of completion.
3. Traverse the current queue in numeric sibling order, recursively selecting
   the first unclaimed, dependency-ready leaf; use upcoming work when no current
   leaf is ready, including required prerequisites.
4. Check the coordination gates below before editing shared consumers. Do not
   take over occupied work without explicit authorization.

Baseline coverage: all 102 task nodes (83 leaves), their dependency declarations,
current worktrees, and relevant integration history. Spot checks included
server journal storage and module dependencies, AgentD handshake/control and
native consumers, SSH key ownership, and HTTP material consumers. This is a
task/dependency audit, not an exhaustive implementation or correctness review.
Readiness means no known planning blocker; tests were not run for this audit.

This audit records gates and supporting evidence; task status, membership, and
local selection order are maintained only in the filesystem task tree.

## 1. Material and configuration

Two branches can advance independently: material ownership, and ACL/configuration.
Coordinate edits to bootstrap, schema, configuration mutation, and terminal
administration with their active owners.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| M1 | [Remaining key owners](current-work/01_unified-key-material-bootstrap/01_remaining-key-owner-migration.md) | **Next overall**; remove the remaining independent key storage. |
| M2 | [JWT rotation and refresh](current-work/01_unified-key-material-bootstrap/02_short-lived-jwt-rotation-and-refresh.md) | Identity foundation is integrated; may run beside M1 if consumers do not overlap. |
| M3 | [Rotation/recovery acceptance](current-work/01_unified-key-material-bootstrap/03_rotation-recovery-and-acceptance.md) | M1 + M2; encrypted configuration foundation is integrated. |
| C2 | [Canonical repository names](upcoming-work/01_acl-storage-hardening/03_canonical-repository-names.md) | Coordinate with Git parser/storage boundary; one storage-neutral identity. |
| C3 | [Local path containment](upcoming-work/01_acl-storage-hardening/04_local-path-containment.md) | After characterization and canonical path decisions. |
| C4 | [Exact snapshot save](upcoming-work/01_acl-storage-hardening/05_exact-snapshot-save.md) | Preserve the established path boundary and publication semantics. |
| C5 | [Consistent storage failures](upcoming-work/01_acl-storage-hardening/06_consistent-storage-failures.md) | Migrate the settled read/write contract once. |
| C6 | [Hierarchical authorization](current-work/02_hierarchical-orion-configuration/01_hierarchical-authorization.md) | ACL foundation first, as required by its parent; users/roles are integrated. |
| C7 | [Native Git configuration snapshots](current-work/02_hierarchical-orion-configuration/02_native-git-configuration-snapshots.md) | Can advance beside remaining C2–C6; Local characterization is integrated. Coordinate bootstrap ownership. |
| C8 | [Configuration administration/acceptance](current-work/02_hierarchical-orion-configuration/03_administration-and-acceptance.md) | C6 + C7 and integrated repository/mirror configuration. |

## 2. Agent control, replication, and commands

The server owns durable agent/launch identity and journal cursors. AgentD owns
its outbound connection and discovers independent session hosts. Implement the
control foundation before attaching replication and command routing to it.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| A1 | [Agent/launch records](current-work/03_agent-session-server/01_control-and-registries/01_agent-and-launch-records.md) | **Next server task**, available. |
| A2 | [Server launch/reconnect authentication](current-work/03_agent-session-server/01_control-and-registries/02_launch-and-reconnect-authentication.md) | A1; HTTP/2 transport is integrated. |
| A3 | [Connection ownership/health](current-work/03_agent-session-server/01_control-and-registries/03_connection-ownership-and-health.md) | A2. |
| A4 | [Session reconciliation/ownership](current-work/03_agent-session-server/01_control-and-registries/04_session-reconciliation-and-ownership.md) | A3. |
| A5 | [Server runtime/provisioning integration](current-work/03_agent-session-server/01_control-and-registries/05_runtime-and-provisioning-integration.md) | A1–A4; use current AgentD and a live protocol peer. |
| D1 | [AgentD handshake/authentication](current-work/04_agentd/01_control-connection-lifecycle/01_handshake-and-authentication.md) | Available beside A1; agree authentication behavior with A2. |
| D2a | [Reconnect/heartbeat](current-work/04_agentd/01_control-connection-lifecycle/02_reconnect-and-heartbeat.md) | D1; counterpart A3. |
| D2b | [Discovery/reporting](current-work/04_agentd/01_control-connection-lifecycle/03_session-discovery-and-reporting.md) | D1; independent of D2a; counterpart A4. |
| D3 | [AgentD control runtime/acceptance](current-work/04_agentd/01_control-connection-lifecycle/04_runtime-and-acceptance.md) | D2a + D2b + A5. A5 does not depend on D3. |
| J1 | [Server session replication](current-work/03_agent-session-server/02_session-replication.md) | A5; durable journal storage is integrated. The old worktree does not make this ready. |
| J2 | [AgentD journal sync](current-work/04_agentd/02_journal-sync.md) | D3 + J1 for integrated delivery; settle ACK sequence allocation with command orchestration. |
| J3 | [Remaining native contract alignment](current-work/04_agentd/03_session-host-contract-alignment/TASK.md) | Audit against integrated focused fixes before coding; finish with J2 and K2. Do not make the whole audit a prerequisite of those implementations. |
| K1 | [Server command service](current-work/03_agent-session-server/03_command-service.md) | A5; recommend J1 first for durable command evidence. Agree retry/unknown-result semantics with K2. |
| K2 | [AgentD command orchestration](current-work/04_agentd/04_command-orchestration.md) | **Paused/owned**; journal sync, source-aware controls, and N6b recovery first. |
| K3 | [Diagnostic secret redaction](current-work/04_agentd/05_diagnostic-secret-redaction.md) | K2; required before AgentD release. |
| K4 | [Platform status/resilience](current-work/04_agentd/06_platform-status-and-resilience.md) | D3 + J2 + K2. |
| E1 | [Historical/live event API](current-work/03_agent-session-server/04_live-event-api.md) | J1 and integrated storage; can advance beside command routing. |

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
| N1 | [Linux process-tree control](current-work/05_native-session-host/01_linux-process-tree-control.md) | **Owned**; platform process identity and discovery boundary. |
| N1a | [Linux proc access failures](current-work/05_native-session-host/02_linux-proc-discovery-errors.md) | Reproduce first; coordinate the same discovery code with N1. Recheck after N1 lands. |
| N2a | [Process listing/addressed signals](current-work/05_native-session-host/03_process-control-and-pty-closure/01_list-processes.md) | **Parent owned**; use N1's identity contract. |
| N2b | [PTY closure](current-work/05_native-session-host/03_process-control-and-pty-closure/02_pty-closed.md) | **Parent owned**; common journal/control contract, separate from process liveness. |
| N3 | [Explicit termination](current-work/05_native-session-host/04_termination-coordination.md) | N2a + N2b; keep policy outside the host. |
| N4 | [Shutdown hardening](current-work/05_native-session-host/05_termination-shutdown-hardening.md) | N3 + N2; recommend before the final lifecycle simplification. |
| N5 | [Lifecycle ownership simplification](current-work/05_native-session-host/06_simplify-session-lifecycle.md) | N2 + N3; audit remaining duplication after N4, do not reimplement PTY closure. |
| N6a | [Source-aware controls](current-work/05_native-session-host/07_source-aware-controls.md) | Native-control baseline is integrated. Add source identity and connection-scoped manual sequencing without taking ownership of AgentD recovery. |
| N6b | [SERVER sequence recovery](current-work/05_native-session-host/08_server-operation-sequence-recovery.md) | N6a; establish one authoritative recovery rule before K2/J2 allocate server operations after reconnect. |
| N7 | [Harness event ingress](current-work/05_native-session-host/09_harness-events.md) | Existing journal/Unix baseline suffices; recommended after shared control changes to avoid competing protocol edits. |
| N8 | [Windows host](current-work/05_native-session-host/10_windows-host.md) | Bootstrap work can start independently; finish parity against the settled common control/PTY/source contracts. |
| N9 | [Native packaging/acceptance](current-work/05_native-session-host/11_release-and-acceptance.md) | All required native siblings, including Windows; do not silently narrow the release matrix. |

N3, N4, and N5 overlap in blocked input, termination, and lifecycle ownership.
Their distinct outcomes are entry/signal delivery, finalization, and removal of
remaining duplicate state. At each step, inspect the preceding result and
close already-satisfied scope with evidence instead of adding another coordinator.

## 4. Git protocol, client, storage, and transports

Wire and client changes are separate branches with shared-consumer coordination.
Review storage after its boundary stabilizes, before externalizing it.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| G1 | [Canonical Git object ID](upcoming-work/02_git-wire-architecture-simplification/01_canonical-git-object-id.md) | First wire task. |
| G2 | [Blocking output migration](upcoming-work/02_git-wire-architecture-simplification/02_blocking-output-migration.md) | Follow G1; delete resumable output machinery. |
| G3 | [Parser/storage boundary](upcoming-work/02_git-wire-architecture-simplification/03_parser-storage-boundary.md) | G2; coordinate canonical repository names C2. |
| G4 | [Repository command context](upcoming-work/02_git-wire-architecture-simplification/04_repository-command-context.md) | G3; resolve repository resources once. |
| G5 | [Capability advertisement policy](upcoming-work/02_git-wire-architecture-simplification/05_global-capability-advertisement-policy.md) | G4; one veto at settled composition points. |
| G6 | [Native storage architecture review](upcoming-work/05_git-native-storage-architecture-review.md) | G1 + G3 + G4; can run beside G5. |
| G7 | [Wire re-audit](upcoming-work/02_git-wire-architecture-simplification/06_post-simplification-review.md) | G1–G6; reuse the storage review. |
| L1 | [Single-session request planning](upcoming-work/03_git-client-architecture-simplification/01_single-session-request-planning.md) | First client task; coordinate proxy/upstream consumers with their owners. |
| L2 | [Phase-aware exchange](upcoming-work/03_git-client-architecture-simplification/02_phase-aware-transport-exchange.md) | L1. |
| L3a | [Factual failure model](upcoming-work/03_git-client-architecture-simplification/03_factual-failure-model.md) | L2. |
| L3b | [Session inactivity timeout](upcoming-work/03_git-client-architecture-simplification/04_session-inactivity-timeouts.md) | L2; can run beside L3a with shared-file coordination. |
| L4 | [Blocking report-status parser](upcoming-work/04_git-report-status-blocking-parser.md) | Recommended after L2/L3 and before parity/re-audit; removes a second parsing path. |
| L5 | [Smart HTTP/SSH parity](upcoming-work/03_git-client-architecture-simplification/05_smart-http-ssh-behavior.md) | L1–L3 required; include L4's completed parser behavior. |
| L6 | [Client removal re-audit](upcoming-work/03_git-client-architecture-simplification/06_post-simplification-removal-review.md) | L1–L5 + G3. |
| G8 | [Server transport review](upcoming-work/06_git-server-transport-architecture-review.md) | G7 + L6 + completed remote proxy bootstrap R1–R4. |
| G9 | [Virtual-thread SSH/Jetty transport](upcoming-work/07_virtual-thread-jetty-ssh-git-transport.md) | G8; finish HTTP hardening H1–H2 before changing Jetty execution. |
| G10 | [External repository storage](upcoming-work/08_externalized-repository-storage.md) | G6 and its accepted prerequisite follow-ups; choose the concrete backend before extraction. Independent of G9. |

G9 and G10 contain implementation checklists inside their leaf files.
Treat each as one task until its reviewed design supports
a bounded split. Do not claim an untracked checklist item as a task node.

## 5. Remote Git bootstrap and synchronization

A bootstrap proxy and repository synchronization have different runtime duties,
but must consume the same canonical Git client/storage and secret mechanisms.

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| R1 | [Bootstrap proxy runtime](current-work/06_remote-git-proxy-bootstrap/01_bootstrap-proxy-runtime.md) | **Owned**, and parent owned; integrate the existing work first. |
| R2 | [Proxy configuration/secrets](current-work/06_remote-git-proxy-bootstrap/02_proxy-config-and-secrets.md) | R1; typed material/encryption foundations are integrated; parent remains owned. |
| R3 | [Proxy admin UI](current-work/06_remote-git-proxy-bootstrap/03_proxy-admin-ui.md) | R2; coordinate terminal administration and configuration mutation. |
| R4 | [Native proxy integration migration](current-work/06_remote-git-proxy-bootstrap/04_native-integration-test-migration.md) | R1–R3. |
| R5 | [Git configuration fixture repair](upcoming-work/09_git-configuration-integration-fixtures.md) | Reproduce/check after R4; retain only unresolved fixture work, no second proxy implementation. |
| S1 | [Primary upstream synchronization](current-work/07_external-git-repository-sync/01_primary-upstream.md) | **Paused/owned**; foundation is integrated, implementation remains on its branch. |
| S2 | [Branch filtering](current-work/07_external-git-repository-sync/02_branch-filtering.md) | S1 integrated first. |
| S3 | [GitHub commit replication](upcoming-work/10_github-commit-replication.md) | Reconcile the older plan with S1/S2 and the parent GitHub checklist before starting. |

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
| H1 | [Unified HTTP invocation](upcoming-work/11_http-core-hardening/01_unified-route-invocation.md) | Available foundation; coordinate route consumers under active administration work. |
| H2 | [Typed route matching](upcoming-work/11_http-core-hardening/02_typed-route-matching.md) | H1. |
| T1 | [Terminal administration](current-work/08_interactive-ssh-shell/01_terminal-administration.md) | **Owned**; integrate terminal interaction/configuration work. |
| T2 | [SSH PTY timeout](upcoming-work/12_ssh-pty-completion-timeout.md) | Recheck after T1; fix only a remaining reproducible failure. |
| T3 | [Streaming monitoring](current-work/08_interactive-ssh-shell/02_streaming-monitoring.md) | Core/terminal baseline is integrated; recommend T1/T2 first because rendering and cancellation overlap. |
| T4 | [AgentD local terminal](current-work/04_agentd/07_local-terminal.md) | J2 + K2 + N6a; offline manual mode still uses the canonical control/journal path. |
| T5a | [Web terminal](current-work/03_agent-session-server/05_web-terminal.md) | K1 + E1; end-to-end verification also needs K2/J2. |
| T5b | [SSH session PTY gateway](current-work/08_interactive-ssh-shell/03_session-host-pty-gateway.md) | Server control + K1 + E1; terminal foundation integrated. Can advance beside T5a. |
| T6 | [SSH security/acceptance](current-work/08_interactive-ssh-shell/04_security-and-acceptance.md) | Required shell siblings T1 + T3 + T5b, with T2 resolved or shown obsolete. |

## 7. Release, deployment, and final reviews

| Order | Existing task | Gate / reason |
| --- | --- | --- |
| P1 | [AgentD packaging/MVP](current-work/04_agentd/08_release-and-acceptance.md) | All AgentD siblings, T4, and native packaging N9. |
| P2 | [Server MVP acceptance](current-work/03_agent-session-server/06_release-and-acceptance.md) | Server siblings through T5a; shared scenario requires working AgentD commands/replication. Can share acceptance evidence with P1. |
| P3 | [Remote provisioning administration/acceptance](current-work/09_remote-machine-provisioning/01_administration-and-end-to-end-acceptance.md) | Packaged AgentD/native host, A5, protected configuration, and existing SSH enrollment/recovery. No separate provisioning registry. |
| P4 | [Agent control-plane review](upcoming-work/13_agent-session-control-plane-architecture-review.md) | P1 + P2. |
| P5 | [Runtime composition review](upcoming-work/14_runtime-composition-root-architecture-review.md) | C8 + M3 + G8. It need not wait for unrelated platform work or G9/G10. |

P1/P2 are acceptance joins, not mutual implementation dependencies. P3 consumes
packaged/runtime results; A5 uses existing provisioning interfaces and therefore
does not wait for P3.

## 8. Maintenance and optional platform work

| Existing task | Disposition |
| --- | --- |
| [Stale branches/worktrees](current-work/10_stale-branches-and-worktrees.md) | **Paused/owned**; preserve unique work and regression coverage. Not a global prerequisite. |
| Stalled-operation watchdog | Complete in `ad1c5b97`; excluded from the task queue. |
| [macOS process inspection](upcoming-work/15_macos-agentd-process-inspector.md) | Decide cooperative-only versus forced recovery before designing a helper. Not a Linux production-release gate. |
| [Control request queue](upcoming-work/16_session-host-control-request-queue.md) | Explicitly deferred; require observed need, compare a simple admission limit first. |

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
