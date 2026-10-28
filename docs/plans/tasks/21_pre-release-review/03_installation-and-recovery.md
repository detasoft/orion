# Verify Packaged Installation and Recovery

Status: todo

## Requirements

Install, initialize, restart, back up, and restore Orion from the distributed
artifact using only public operator instructions. Preserve key material and
consistent repository/configuration/ACL generations.

## Design Decisions

- Use the existing distribution and launcher. A source-tree launch or manual
  repair of extracted files does not demonstrate a working installation.
- Follow the documented consistent-generation backup boundary; do not imply
  cross-store atomicity that the storage implementation does not provide.
- Declare supported OS/architecture combinations from actual evidence. A
  macOS run does not establish Linux or Windows support.

## Implementation Plan

1. Reproduce the archive permission defect recorded for the diagnostic
   `c815f210` distribution: `orion/bin/orion` and `orion/runtime/bin/java` had
   mode `0644`, and direct startup failed with exit 126. Fix archive assembly
   to preserve required executable modes and verify a freshly extracted archive.
2. Build the candidate through ordinary `make dist`, without disabling tests.
   Install it outside the checkout on a clean supported host and run the bundled
   launcher/JVM without chmod repairs or developer path overrides.
3. Follow the public first-start password and key-enrollment instructions,
   verify the service endpoints, then stop and restart using preserved state.
4. Back up and restore repository data, versioned configuration/ACLs, and
   protected key material as a consistent generation. Verify restored access
   and data, including failure when required material is missing and absence
   of accidental key regeneration.
5. Exercise the documented upgrade and rollback procedure. Correct public
   instructions for persistent paths, credentials, service management, host
   requirements, and recovery limits.

## Dependencies

Use the identified candidate from `01_reproducible-build.md`. The archive-mode
repair may proceed while other candidate checks are being resolved. Operational
claims must agree with `02_git-and-authorization.md` and the security review.

## Acceptance Criteria

- A fresh extracted archive starts directly with its bundled runtime and has
  the correct executable permissions on each declared platform.
- A new operator can initialize, enroll, restart, back up, restore, upgrade,
  and roll back without undocumented development steps.
- Restored data and access match the backed-up generation; missing key material
  fails safely without silently replacing the original keys.
