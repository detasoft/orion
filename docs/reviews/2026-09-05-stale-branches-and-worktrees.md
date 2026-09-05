# Stale branches and worktrees review

Date: 2026-09-05, Europe/Amsterdam.
Baseline: `1a66be8a7676bfcc7acae135aad43b7884855b0f` (`main` before the review claim).
Review claim: `978c1366`. This review changes only documentation.

## Decision

The audit is complete. No branches or worktrees were deleted. Ancestry is
sufficient for three candidates, but ownership prevents treating the two
associated worktrees as abandoned. The three divergent branches retain useful
material. Deletion of their unique work requires explicit approval under the
review task's safety rules.

| Candidate | Head | Commits outside main | Recommendation |
| --- | --- | ---: | --- |
| `work` | `a51cfe9b` | 0 | Remove candidate: integrated, no registered worktree. |
| `codex/primary-upstream-sync-6e3b` | `949644e6` | 0 | Retain pending release by the paused task owner; then remove branch and worktree. |
| `codex/agentd-command-orchestration-d8e4` | `72941080` | 0 | Retain pending release by the paused task owner; then remove branch and worktree. |
| `codex/git-wire-response-parsers` | `cdcdf356` | 50 | Retain useful regression scenarios; production architecture is superseded. |
| `native-git-client-session-machines` | `fbe3e78a` | 47 | Retain useful close-failure tests; production architecture is superseded. |
| `transfer` | `4ab89b48` | 129 | Retain: 24 infrastructure files are absent from main history. |

These are local-ref recommendations. Remote branches were neither changed nor
refreshed. No runtime correctness or passing-test claim is made by this audit.

## July branches: common history

The branches share `ac7e78a8` and 44 earlier commits outside main ancestry.
`git cherry main <branch>` finds only `b81aae28` (ignore local worktrees) as
patch-equivalent on each branch. This does not mean all remaining work is lost:
main rebuilt these components through different commits and APIs.

- The native storage foundation from `85edfbff` and upload selection from
  `9bab22a0` have replacements introduced in main by `4c915c3a`, extended by
  `f04efe9a`, `fd3218e5`, and subsequent native repository work. Current code is
  under `git/git-native-storage`, with publication in `NativeGitRepository`
  and transport service orchestration under `net/git-transport`.
- The common regression fix `ac7e78a8` added object-before-ref publication and
  all-stale protection. Current `LooseRefStoreTest` retains
  `batchPublishesObjectsBeforeMakingSuccessfulRefsVisible` and
  `batchDoesNotPublishObjectsWhenEveryUpdateIsStale`; `NativeGitRepositoryTest`
  covers quarantine publication. Main's `783dd483` and `2ded1c84` further
  exercise receive-pack publication ordering.
- The old rejection of delete and atomic capabilities is superseded by
  supported operations. `GitBlockingWireSessionTest` covers deletion,
  non-atomic stale commands, and atomic rollback. Preserve these current
  contracts rather than importing the old rejection behavior.
- Pkt-line, side-band, report-status, and capability work now lives in
  `git/git-parser` and the blocking client. `GitBlockingWireTransportTest`
  covers buffered framing, malformed headers, packet writing and side-band
  splitting. `GitBlockingClientsTest` covers streamed fetch and receive status,
  including the later hardening in `f354240f`.
- The branch's generic phase/action machinery is an internal implementation,
  not a preserved wire contract. Main changed wire parsing in `647bf46a`, then
  removed production wire continuations in `5b3921e3`; client replacement
  commits are `5134beaf`, `ede818ee`, and `63f79cef`.

This is a comparison of behavior and representative regression coverage, not
an assertion of byte-for-byte or exhaustive behavioral equivalence.

## codex/git-wire-response-parsers

The five commits beyond the common history are `463f16c5`, `9e9fcfaf`,
`31440e8d`, `9352c205`, and `cdcdf356`: design/plan, typed value stack, semantic
wire phases, and a v1 advertisement parser. The plan also describes v2 ls-refs
and fetch response work; that description is not evidence of implementation
in this branch's five-commit extension.

Current replacement:
`git/git-client/src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java`
(`readAdvertisement`, `parseAdvertisement`) reads fragmented packets until
flush, extracts capabilities, refs and peeled tags, accepts the empty-repository
sentinel, and rejects malformed object IDs, duplicate ordinary refs, and a
peeled ref without its base. Ref validation rejects NUL capability suffixes on
subsequent ordinary ref rows. The internal stack and semantic phase API need
not be restored.

Useful material to preserve is in `9352c205`:
`core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/advertisement/GitV1AdvertisementMachineTest.java`.
It explicitly covers:

- capabilities, refs and peeled tags together;
- fragmentation inside headers, capabilities and ref payloads;
- the empty-repository sentinel;
- capabilities on a second row, orphan peeled refs, malformed IDs and duplicate refs;
- response-end in place of flush and end-of-input before flush.

Current `GitBlockingClientsTest` directly covers fragmented fetch, shallow
advertisement, empty discovery and early EOF. `GitRemoteAdvertisementTest`
checks the result model, not all wire parser rejection cases above. No equivalent
focused current test set was found for those advertisement edge cases. This
is a coverage gap, not a demonstrated runtime defect. Keep the branch until
these useful scenarios are adapted to the canonical blocking client, or the
owner explicitly accepts their removal. Do not cherry-pick the obsolete parser
and stack wholesale.

## native-git-client-session-machines

Its two commits beyond the common history, `f2865758` and `fbe3e78a`, add
`GitClientAction`, `GitClientMachine`, and `GitProtocolSessionMachine`, plus
scripted transport tests. Main's `GitBlockingClientExecutor` now owns opening,
operation execution, failure conversion and closing. `GitUploadPackClient`
and `GitReceivePackClient` use the current transport-session API.

Current `GitBlockingClientsTest` covers fragmented operation success, early EOF,
open/authentication and read failures, unchecked callback failure with close,
blocked-session timeout, and a session opening after cancellation. ByteBuf
handoff and client-machine cleanup assertions from the old tests are specific
to the removed architecture.

Still useful in `fbe3e78a`:
`core/git-protocol-client/src/test/java/pro/deta/orion/git/client/machine/GitProtocolSessionMachineTest.java`
contains `closeFailureBecomesPrimaryAfterSuccessfulExchange` and
`closeFailureIsSuppressedOnThePrimaryExchangeFailure`. The current executor
has corresponding close-error handling, including suppression when the primary
failure has a cause, but no equivalent close-error injection tests were found
in `git/git-client/src/test`. Adapt those two scenarios to current result and
cause semantics before discarding the branch, or obtain explicit acceptance
of their removal. Retain meanwhile; restoring the old action API is unnecessary.

## transfer

`git cherry main transfer` reports 128 patch-equivalent commits and one
non-equivalent commit, `b977692f` (initial self-hosted Git implementation).
The corresponding main commit is `dc56501a`.

Two full-tree comparisons establish the precise difference:

- `git diff b977692f dc56501a` contains only 24 removed files under `infrastructure/`.
- `git diff transfer 995d18aa` contains the same 24 removed files, 1,228 lines.
  Excluding `infrastructure/`, both comparisons are empty. `995d18aa` is main's
  counterpart of transfer's final licensing commit.

The unique directories contain Terraform/Packer AMI setup and its README,
Makefile and Cloudflare update script (`infrastructure/orion-ami`), root-user
provisioning (`infrastructure/root-user`), and SSO setup (`infrastructure/sso`),
plus infrastructure ignore files. Main has no `infrastructure/` tree and no
path history for it. The application/test/licensing work is integrated;
these infrastructure files are not. Their operational suitability was not
tested, and deployment-specific values were not copied into this report.
Retain `transfer` until this material is deliberately preserved elsewhere or
explicitly discarded. The 128 equivalent patches do not justify deleting it.

## Integrated branches and ownership

`work` is an ancestor of main and has no registered worktree. It has no unique
committed work to rescue; it is the only unconditional removal recommendation
from the local Git evidence. Its `origin/work` tracking relationship does not
require deletion or modification of that remote ref.

`codex/primary-upstream-sync-6e3b` and its worktree
`.worktrees/primary-upstream-sync-6e3b` are integrated through `949644e6`.
The foundation includes attach planning, sync state and remote gateway code in
`git/git-sync`. The [primary-upstream task](../plans/current-work/external-git-repository-sync/primary-upstream/TASK.md)
still belongs to session `6e3b`, paused 2026-09-04 01:25; outbound serialization,
retry and minute audits remain unfinished. The worktree has no tracked changes
or untracked non-ignored files, but contains ignored `.orion-cache/`, build
outputs and UI dependencies. Do not infer disposable local state from a clean
short status alone.

`codex/agentd-command-orchestration-d8e4` and its worktree
`.worktrees/agentd-command-orchestration-d8e4` are integrated through `72941080`.
Design `de5a3ebf` and plan `013298c9` are already on main. The
[command-orchestration task](../plans/current-work/agentd/command-orchestration/TASK.md)
remains owned by session `command-orchestration-d8e4`, paused 2026-09-03 19:51
on journal/control prerequisites. This worktree has no tracked, untracked, or
ignored status entries at inspection. Its pause commit is not task completion.

No owner release or absence of a live session was established for either
worktree. Retain both until ownership is released. Any later authorized cleanup
must recheck the exact refs, registered paths, all local files and ownership
immediately before removal, preserving both unfinished task nodes.

The other six worktrees listed in the task (interactive-terminal,
journal-durability, linux-process-tree-control, organization-users-roles,
query-and-output, session-replication) were outside scope and were not modified.

## Verification and scope

Read-only checks: `git worktree list --porcelain`, local heads and dates,
`git rev-list --count main..<candidate>`, `git cherry`, branch-exclusive logs,
full-tree comparisons for transfer, current and historical production/test
source inspection, and both candidate worktrees' normal and ignored status.
Current task ownership was read from main. Git evidence was rechecked before
committing the report; links and `git diff --check` were also checked.

The main worktree was clean at the start. Other sessions advanced main during
the audit and edited the streaming-monitoring task; those changes are outside
this review and were left untouched. All six candidate heads stayed unchanged.
No source code, tests, candidate refs,
worktree files or other task ownership were changed. Maven was not run because
this was a review with documentation-only commits, as required by AGENTS.md
and the review verification-ownership rule.

## Cleanup follow-up, 2026-09-05

At the user’s request to start with the simplest candidates, removed local
`work` (`a51cfe9b`) using `git branch -d work`. Immediately before deletion,
`git merge-base --is-ancestor work main` succeeded, `git rev-list --count
main..work` returned zero, and no registered worktree used that branch.
The audit above records the earlier state; the other five candidates remain
retained for the reasons documented there. Remote refs and worktrees were
unchanged. Next: adapt the two close-failure regression tests from
`native-git-client-session-machines` before reconsidering that branch.
