# Logging and Audit Sink Concept

## Goal

Define an Orion-owned logging concept for operational records that are not just
process logs. The first targets are access logs and application event logs, but
the design should leave room for other structured logging purposes without
adding a new ad hoc writer for each feature.

## Scope

Create a generic logging/audit sink abstraction that can accept structured
records from different parts of the application:

- HTTP and SSH access records, including timestamp, authenticated user when
  available, remote address, method or command, target resource, status, and
  request id;
- application events published through `OrionEventManager`, including event
  type, source, timestamp, request id when available, and processing outcome;
- lifecycle events such as startup, shutdown request, shutdown completion, and
  failed lifecycle task details;
- future domain records, for example repository receive/fetch summaries,
  administrative configuration changes, token issue events, and ACL updates.

This should be separate from SLF4J process logging. Process logs are for
developers and operators reading the server log stream; structured access and
event records are data that can be stored, queried, rotated, or exported.

## Design Notes

Start with a small domain model for log records rather than a concrete file
format. A record should have a stable type, timestamp, optional request id,
principal, source component, structured attributes, and a severity or outcome
where that is meaningful.

Keep sinks pluggable:

- file sink for local access logs and event logs;
- in-memory sink for tests;
- no-op sink for disabled logging;
- future sinks for repository-backed storage, object storage, or external
  collectors.

Avoid coupling HTTP access logs to HTTP code only. HTTP routes, SSH commands,
event handlers, and lifecycle code should depend on the same small logging
interface. Transport-specific code can enrich records with protocol-specific
fields before submitting them.

Define retention and rotation as part of the concept, even if the first
implementation only supports a simple local file sink. Access logs may grow
quickly, so the storage path, rotation policy, and failure behavior must be
explicit in configuration.

Event logging must not block the event loop or request path indefinitely. If a
sink is slow or unavailable, Orion should either buffer within a bounded limit
or fail according to an explicit policy. The default should preserve server
availability and surface sink failures through process logs.

## Open Questions

- Should access logs and event logs share one physical sink with different
  record types, or use separate configured sinks by default?
- Should records be written as JSON lines, a compact binary format, or an
  append-only repository/object-storage structure?
- Which records are operational diagnostics, and which are audit records that
  require stronger durability and tamper-evidence?
- How should request ids be propagated across HTTP, SSH, Git operations, ACL
  reload events, and lifecycle tasks?
- What should the default retention policy be for local development and for a
  long-running server?

## Verification

Cover at least these cases when implementing the concept:

- HTTP requests produce access records for success, authorization failure, and
  route-not-found responses;
- SSH commands produce access records for success and denied access;
- published application events can be logged without changing each event
  handler;
- logging failures do not prevent normal request completion under the default
  policy;
- local file sinks rotate or reject writes according to configured limits;
- tests can assert emitted records through an in-memory sink without scraping
  process logs.
