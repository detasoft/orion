# Explicit Force and Force-With-Lease Push Semantics

Status: todo
Depends on: 01_single-session-request-planning.md,
03_factual-failure-model.md

## Required Result

Provide explicit ordinary, force, and force-with-lease behavior when Orion
plans a push. Ordinary branch pushes must reject history rewrites before
sending update commands. A caller must deliberately request forced behavior;
server-side permission to force is not evidence of caller intent.

Support both explicit per-ref lease expectations and expectations supplied
from the caller's previously observed remote-tracking state. Keep these
expectations separate from the fresh receive-pack advertisement.

## Verified Protocol Semantics

- Receive-pack sends `old-id new-id ref-name`, followed by the pack. There is
  no standard force or force-with-lease command field, capability, or push-option.
- Git's client uses force to override its local push rejection checks. It still
  sends the advertised old object ID, so concurrent updates after advertisement
  remain protected by the server's old-ID comparison.
- For a lease, the client compares its independently supplied expectation with
  the advertised old ID before sending that ref's command. A matching lease
  permits a history rewrite; a mismatch rejects a proposed change. The subsequent server
  comparison protects against changes after advertisement.
- The receiver cannot distinguish ordinary force from force-with-lease by the
  command bytes. It classifies the actual update and applies authorization.
- `atomic` is an independent negotiated capability controlling the group of ref
  updates; it does not express force intent or a distributed transaction.

Evidence, checked 2026-09-14:

- [Git push options](https://git-scm.com/docs/git-push): force, explicit leases,
  remote-tracking leases, and the interaction of combined options.
- [Receive-pack request grammar](https://git-scm.com/docs/gitprotocol-pack#_reference_update_request_and_packfile_transfer).
- [Git remote.c](https://github.com/git/git/blob/master/remote.c):
  `set_ref_status_for_push` and `apply_cas` implement client-side decisions.
- [Git send-pack.c](https://github.com/git/git/blob/master/send-pack.c):
  `send_pack` serializes the selected commands without force/lease fields.

## Current Model and Scope

`GitReceivePackRequest` currently contains prebuilt commands, a `GitPackSource`,
and `atomic`. `GitReceivePackClient` and `GitBlockingClientWire` send those
commands without an explicit caller-intent or lease-planning contract.
`DefaultGitNativeRepositoryService.isForceUpdate` classifies incoming updates
and invokes `GitNativeRepositoryAccessHook.beforeUpdate`; the storage layer
checks expected old IDs.

Use the operation-scoped planner established by the dependencies. Put push
intent and lease checks at the client planning boundary, with access to the
local object reader needed for ancestry decisions. Keep wire encoding and
storage compare-and-set independent of those client options.

Affected consumers include native bootstrap/proxy push, remote synchronization,
and the subsequent remote-file write API. Inspect their actual overwrite and
conflict requirements; select modes explicitly without turning every existing
raw ref replacement into an unrestricted force operation.

## Design Decisions

- Use one unambiguous push policy per affected ref: ordinary, force, or
  force-with-lease. Reuse existing request/planning types where possible rather
  than introducing another client service or transport.
- Ordinary branch updates require fast-forward ancestry. If local data cannot
  establish that relationship, return a factual planning rejection instead of
  assuming force. Preserve Git's separate branch/tag/create/delete rules.
- Force deliberately permits a rewrite but does not bypass server authorization,
  protected-ref rules, object validation, or the wire old-ID comparison.
- A lease carries an expected object ID or explicit expected absence. An absent
  expectation must not mean unrestricted force. Tracking-based forms resolve
  expectations from caller-owned observed state; do not add a tracking database
  solely to implement these options or infer an expectation from advertisement.
- Cover Git's `--force-with-lease`, `--force-with-lease=<ref>`, and
  `--force-with-lease=<ref>:<expect>` meanings through the structured API. An
  expected-absence lease must detect a ref created after advertisement even if
  its target equals the proposed new target. Characterize Git's already-up-to-date
  handling before lease evaluation separately from an accepted ref mutation.
  Document behavior when tracking data is unavailable. CLI parsing is not
  required by this task.
- Prefer mutually exclusive structured modes. If an existing entry point accepts
  combined Git-style force and lease options, follow Git's documented precedence
  explicitly; do not silently weaken a structured lease into force.
- Plan against the advertisement of the same push operation. Retain the original
  lease across transport retries and proxy forwarding; changing that expectation
  requires a new caller decision. A proxy must not replace the incoming old ID
  with a newer upstream tip merely to make a rejected update succeed.
- Report local non-fast-forward and lease rejection separately from server
  access denial, server stale-ref rejection, and transport failure. Do not
  report successful publication when an atomic batch was aborted.
- Internal entry points retain the existing access hook. A trusted caller may
  supply `ALLOW_ALL`, which affects authorization only, not an explicitly
  requested lease or compare-and-set condition.
- Keep `--force-if-includes`, reflog heuristics, a new CLI, the common pack-write
  architecture, and broader protected-ref policy out of this task.

## Implementation Plan

1. Trace final client planning contracts and all push callers after dependencies
   land. Map ordinary, forced, and conditional replacement requirements to the
   smallest shared policy representation.
2. Implement ancestry and lease decisions before update commands and outgoing
   pack generation/transfer. Resolve tracking expectations outside transport
   discovery and preserve them through the operation.
3. Update native proxy, sync, and other existing callers to express their actual
   intent. Preserve stale conflicts and upstream authorization; remove replaced
   internal entry points without compatibility paths.
4. Preserve standard receive-pack encoding and server-side update classification.
   Repair any demonstrated server compare-and-set gap needed for the lease
   guarantees, including races involving initially absent refs.
5. Add behavioral client, server, and proxy coverage, including interoperability
   with Git CLI fixtures and equivalent Smart HTTP/SSH results. Document the
   caller contract at the canonical API and align dependent task descriptions.

## Acceptance and Verification

- Ordinary fast-forward succeeds; ordinary divergent history is rejected even
  when the server would grant force permission. Missing ancestry data cannot
  silently enable a rewrite.
- Explicit force can replace divergent history when authorized; a denied force
  grant or protected-ref rule still rejects it on Orion's server.
- A matching lease permits a rewrite. A stale lease rejects before sending the
  affected command or generating/transferring its pack, even when the caller
  has force permission or supplies `ALLOW_ALL` for internal authorization.
- A fresh advertisement newer than the caller's lease does not update that
  lease. A second writer after advertisement causes server rejection, including
  for plain force. Use deterministic coordination for both races.
- Expected absence succeeds only for a missing ref and detects concurrent
  creation; tracking-derived expectations and missing tracking state have
  explicit tests. Equal-tip/no-op cases retain a documented, verified outcome.
- Multi-ref tests cover a mixture of matching/stale leases and allowed/denied
  updates with and without `atomic`. Atomic local planning failure sends no
  partial update; non-atomic results identify accepted and rejected refs.
- Capture actual protocol traffic: accepted forced and leased pushes use the
  standard command grammar, no invented force capability or push-option, and
  preserve the expected old IDs. A rejected lease sends no affected command.
- Git CLI pushes using `--force` and `--force-with-lease` interoperate with Orion;
  Orion client modes interoperate with a controlled Git receive-pack server.
- Proxy tests prove upstream movement does not replace the original expectation
  or turn a stale update into a successful overwrite. Ref rejection does not
  claim object rollback or cross-repository atomicity.
- Existing create/delete/tag, ordinary push, and pack-limit behavior remains
  covered. No production Git subprocess or JGit dependency is added.
