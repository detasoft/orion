# Verify Git Transports and Authorization for the Preview

Status: todo

## Requirements

Verify every publicly advertised Git transport and administrative security
boundary, including denial, persistence, and failure scenarios.

## Design Decisions

- Reuse the existing transport and cross-engine test matrix. Distinguish JGit
  transport tests, external CLI smoke tests, and packaged-runtime checks.
- Client Git pull does not imply implemented background synchronization with
  upstream repositories. That work remains in the external-sync task subtree.
- A failure report is evidence to reproduce, not sufficient evidence to change
  a production contract or weaken an assertion.

## Implementation Plan

1. Reproduce the failures recorded for `c815f210`:
   `S3NativeGitRepositoryProviderIT.listsAllPagesAndRejectsCorruptOrMisplacedMetadata`
   returned success for a rejected metadata scenario, and
   `GitSshTransportEndToEndIT.directConfigurationPushRetainsValidStateUntilAValidCommitArrives`
   reported `unavailable` instead of `invalid`. Determine the failing inputs and
   repair the responsible behavior or incorrect test expectation with coverage.
2. Exercise repository creation, clone, fetch, push, and pull over SSH and all
   advertised HTTP(S)/native Git operations. Include invalid/expired credentials,
   unknown SSH keys, and read-only users attempting writes.
3. Verify repository, user, token, and ACL administration, privilege boundaries,
   revocation, and denial paths through the public interfaces.
4. Exercise concurrent and interrupted pushes, restart persistence, credential
   and grant changes, and retention of valid state after invalid configuration.
5. Run the relevant full integration and cross-engine matrix. Record expected
   and observed outcomes in release smoke-test documentation, including any
   explicitly excluded preview functionality.

## Dependencies

Use the candidate and environment identified by `01_reproducible-build.md`.
Failure reproduction may proceed before the full baseline is green. Packaged
runtime checks use the archive verified by `03_installation-and-recovery.md`.

## Acceptance Criteria

- Advertised operations and authorization boundaries have passing evidence for
  the candidate; unsupported preview operations are explicitly excluded.
- The two recorded integration failures are resolved and covered, rather than
  hidden by skipping tests or conflating invalid state with unavailable state.
- Incomplete pushes and denied operations preserve repository/configuration
  integrity, and persisted state remains correct after restart.
