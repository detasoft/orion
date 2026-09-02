# Add Orion Git Client and Server Adapters

Status: todo

Expose the completed Orion native Git client and server paths through the
shared interoperability contracts.

## Scope

- Start `GitNativeTransportService` on loopback port zero with isolated native
  repository storage and allow-all access for matrix tests.
- Provision empty remote repositories with a common `main` default branch and
  expose their `git://` URIs to every client adapter.
- Build the Orion client adapter from `GitUploadPackClient`,
  `GitReceivePackClient`, native repository object creation, pack production,
  pack ingestion, and ref updates.
- Express clone and fast-forward pull as test workflow macros over the public
  Orion discovery/fetch primitives and native repository state.
- Keep pack parsing, ref validation, commit graph traversal, and protocol
  behavior in production Orion components; do not reimplement them in the test
  adapter.
- Declare explicit capabilities for operations the current primitives cannot
  represent and fail matrix completeness checks instead of silently skipping
  required cases.

## Completion Criteria

- A deterministic commit can be pushed by Orion client and cloned or fetched
  from all three server engines.
- JGit and canonical Git clients can push, clone, and pull through the Orion
  native server adapter.
- Orion/Orion transfers are validated by at least one independent read-only
  JGit or canonical Git observation.
