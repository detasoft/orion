# Prepare the First Public Developer Preview

Status: todo

Deliver a reproducible, installable Orion developer preview with release claims
bounded by verified Git, authorization, installation, and recovery behavior.
The proposed version is `0.1.0`; the release owner must choose the actual version.

## Requirements

- Evaluate one identified release candidate and retain evidence of its source
  revision, environment, commands, results, exclusions, and artifact checksums.
- Make the supported transport, deployment, durability, and security boundaries
  explicit. Unfinished agent/session and background synchronization work is not
  implicitly part of the public preview.
- Deliver matching version metadata, license notices, dependency inventory,
  installation instructions, and a tested download path.

## Design

Use the existing Maven distribution, Make verification goals, transport test
matrix, and operator documentation. Repair demonstrated release blockers within
their existing owners; avoid adding features or redesigning architecture merely
to prepare a release.

The original 25 October 2026 inventory was a partial static audit. Subsequent
checks of `c815f210` found integration and packaging failures; those results do
not describe every later revision. The unit-test fixes in `568da22f`, `f2fa4e32`,
`732d5f1d`, and `6b4c36f3` passed a full `make test` on 28 October. This does not
establish a clean-host release build, a passing integration matrix, or release
readiness. Each executable task records the remaining evidence it requires.

## Dependencies

Build evidence identifies the candidate used by transport, installation, and
security verification. Public artifact publication depends on these checks and
the release owner's version and support decisions. External preview validation
may use an identified candidate before publication and may finish alongside the
launch; it must not bypass the artifact and security acceptance boundary.

Existing feature tasks remain authoritative for unfinished implementations.
Preview documentation must either exclude those features or depend explicitly
on their completed acceptance; this task does not claim their work.

## Acceptance Criteria

- The exact release candidate has reproducible build and test evidence, a
  verified packaged installation, and passing advertised transport/auth flows.
- No critical security or data-loss finding remains untriaged. Any accepted
  security finding has an explicit owner decision and deployment constraints.
- Published artifacts, checksums, notices, dependency inventory, release notes,
  and version/date metadata describe the same release.
- An external installation and its feedback are recorded before this aggregate
  task is complete; unsupported features and operational limits are explicit.
