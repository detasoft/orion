# Complete Bidirectional GitHub Mirror Runtime

Status: todo
Depends on: [07/01](01_primary-upstream.md), completed shared Git credential resolution
(`a8cf31a5`, `8750c1ee`).

## Required result

Accept branch changes in both Orion and GitHub and synchronize compatible
histories in both directions. Automatically merge divergent histories when a
three-way merge is conflict-free; retain actual conflicts for manual resolution.
This runtime is required by
[06/07 setup acceptance](../06_remote-git-proxy-bootstrap/07_github-mirror-setup.md);
it must not depend on that UI or its acceptance.

## Current model and scope

Extend the existing repository remote schema, native Git client, `git-sync`
attachment planner, durable state/work, and credential owner. The 07/01 outbound
foundation remains useful but is not the final requested behavior. Its claimed
execution is unchanged. Do not add a second mirror engine or job framework.

All branch refs participate in the first delivery. Apply any subsequently
configured branch filter consistently in both directions; implementing filtering
is not a prerequisite. Webhooks are an independent acceleration in
[07/04](04_github-webhook-wakeups.md). Scheduled polling must work without them.
GitHub repository creation, automatic rebase, force updates, deletes, tag
mirroring, SSH/App credentials, and GitLab support are outside this leaf.

## Design and invariants

Express bidirectional intent in the existing desired-state model and enable it
for the requested setup. Preserve explicitly configured outbound-only behavior
where still required; do not maintain a duplicate legacy production path.

One per-remote execution owner serializes attachment, outbound push, audit, and
inbound fetch. Extend the existing staggered once-per-minute remote audit to
schedule inbound reconciliation when bidirectional intent is enabled. Also
support manual sync. No separate polling service or per-I/O timeout thread is
needed. Reuse durable retry/coalescing and reconstruct pending reconciliation
from current local/upstream refs after restart or a lost response.

Fetch selected upstream refs and publish validated tracking state before
comparing live Orion heads. Plan selected branches together. Equal tips are a
no-op, upstream-ahead tips fast-forward Orion, and Orion-ahead tips schedule an
expected-ID upstream push. Divergent tips require a three-way merge using their
merge base. A clean result creates one ordinary merge commit with both original
tips as parents and publishes that same commit to both sides. No confirmation
is needed for fast-forwards or clean merges. Apply local creates/fast-forwards
and clean merge results atomically with expected old IDs. Concurrent movement
requires fresh observation and replanning;
there is no distributed atomic transaction across Orion and GitHub.

When a selected branch has an unresolved merge conflict, preserve all live and
remote tips, record conflicting tips, merge bases, and conflicting paths, and
stop automatic live-ref publication for that mirror. Never publish conflict
markers or choose one side automatically. Fetch/observation and ordinary local
use remain available. Do not overwrite, force push, or rebase automatically.
An operator fetches both histories, reconciles them with ordinary Git, pushes
the resulting head to Orion, and requests retry. Retry reads both sides again
and resumes only when compatible, including if GitHub moved during resolution.

Use existing native object/tree primitives for merge computation. Inspect for
an existing merge implementation before adding a narrow three-way tree/text
merge component; attachment planning alone is not a merge engine. Define merge
author/committer identity through the existing identity model and keep it stable
across retry. Preserve a created merge commit through local publication so a
lost push response or restart retries the same object rather than creating
repeated merge commits. No shell Git or production JGit path is implied.

Handle clean text and tree merges, including non-overlapping edits in one file.
Never guess for overlapping edits, modify/delete conflicts, incompatible file
types/modes, binary conflicts, or absent/ambiguous merge bases. A case the merge
implementation cannot safely resolve requires an explicit diagnostic and manual
resolution; distinguish unsupported cases from detected content conflicts.

Honor protected refs and existing authorization for each publication. Reject
unsupported delete/force/tag intent explicitly. Removing or disabling a remote
does not remove repository data.

Origin information and observed object IDs prevent inbound updates from creating
redundant outbound work. Convergence to equal tips is a no-op across restarts.
Only introduce a durable synchronization fact if current refs/tracking state and
existing work cannot safely reconstruct it. Keep secret values out of all state
and diagnostic records.

## Implementation plan

1. Extend desired-state validation and the existing coordinator to express the
   requested bidirectional behavior without replacing the repository model.
2. Extend the planner with native three-way merge computation and explicit
   clean/conflicting/unsupported results; use a stable merge identity and
   preserve both parent histories.
3. Extend scheduled observation and manual sync to perform that shared branch
   plan, with native expected-ID publication and bounded transport work.
4. Integrate inbound work, origin-aware coalescing, retry, and restart recovery
   into the existing outbound state owner.
5. Expose safe status, conflict tips, manual sync, and explicit retry through the
   existing runtime boundary consumed by 06/07; UI ownership stays there.
6. Verify concurrent changes, mirror-wide conflict suspension, re-observation
   during resolution, and convergence without echo loops.

## Acceptance

A commit on either side reaches the other through the same configured mirror.
Compatible GitHub changes appear in Orion within a polling interval after a
successful observation, or through manual sync; outbound Orion changes do not
wait for that interval. Transient failures retry and keep local use available.

Divergent clean changes merge automatically, including additions in different
files and non-overlapping edits in one text file. Both sides receive the same
merge commit with both parents and both changes retained. Retrying after restart
or a lost response does not create repeated merge commits.

Overlapping edits and meaningful tree/binary conflicts preserve both tips and
produce actionable manual-resolution diagnostics. Explicit reconciliation by the
operator followed by retry converges both sides. Concurrent movement during
merge publication or retry is rechecked. A compatible branch is not partially
published when the complete plan already contains an unresolved merge conflict
elsewhere. Unsupported merge cases never silently pick a side.

Restart, coalesced changes, repeated sync, lost push responses, and inbound
updates do not lose desired work or create an unbounded echo loop. Test through
native storage/transport and the actual coordinator; include authentication,
protected-ref rejection, and secret redaction. Run focused runtime/transport
checks and the full JVM suite. Real GitHub smoke verification is recorded by
06/07, separately from deterministic local acceptance.
