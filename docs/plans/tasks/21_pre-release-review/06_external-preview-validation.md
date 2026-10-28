# Validate the Preview with an External Operator

Status: todo

## Requirements

Provide a 10–15-minute artifact-based quick start and validate it with at least
one fresh external installation. Capture usability feedback and define the
preview's support and next-feedback commitments.

## Design Decisions

- Use the packaged candidate or release, not a source checkout. Keep verified
  preview features separate from experimental agent/session functionality and
  unfinished background synchronization.
- Transport success is not evidence of product demand. Collect the operator's
  use case, operating effort, blockers, and willingness to continue using Orion.
- Do not invent a tester, SLA, support contact, or milestone date. These are
  explicit decisions and external evidence supplied by the product owner.

## Implementation Plan

1. Write the quick start from the tested installation procedure, including
   initial access setup and push, clone, fetch/pull, and denied read-only push.
2. Document deployment requirements, supported platforms, durability boundaries,
   backup responsibilities, known limitations, and experimental functionality.
3. Coordinate an external tester through the owner and have the operator install
   the identified artifact using only public instructions. Record the artifact
   version, environment, observed smoke results, feedback, and blockers.
4. Resolve release-blocking instruction or behavior defects and repeat the
   affected external scenario. Keep deferred feedback clearly outside release
   support claims.
5. Confirm the DetaSoft managed-deployment/support contact, preview expectations,
   and the owner and timing of the next feedback milestone.

## Dependencies

Requires an installable candidate from `03_installation-and-recovery.md`, the
smoke matrix from `02_git-and-authorization.md`, and agreed security/support
boundaries from `04_security-and-dependencies.md`. An external tester and product
decisions must be available. Validation may finish alongside the public launch.

## Acceptance Criteria

- At least one external operator installs and exercises the artifact using the
  quick start; actual results and feedback are recorded.
- Product and support claims match verified behavior and distinguish unfinished
  features, operational obligations, and known limitations.
- The support contact and next feedback milestone are explicitly agreed, and
  no unresolved external blocker is represented as a successful installation.
