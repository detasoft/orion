# Unify HTTP Route Invocation

Status: todo
Source: finding 2 in [the module review](../../../../../net/http-core/MODULE_REVIEW.md)

Replace the dual `service()`/`handle()` route protocol with one invocation
contract that applies route policy consistently without buffering Git,
packfile, or session-host downloads.

## Scope

- Give every route one public `handle` operation over a small module-owned HTTP
  exchange abstraction.
- Define typed HTTP methods and an executable coarse authorization policy in
  one immutable route definition; enforce both once at the servlet dispatch
  boundary and derive introspection metadata from the same values.
- Let the exchange either send a typed buffered response or open a streaming
  body, preserving streaming and backpressure for Git and binary downloads.
- Keep resource-specific checks, such as repository grants, inside the feature
  handler after route matching and coarse authorization.
- Represent shutdown's after-flush action explicitly instead of retaining a
  second dispatch protocol for that route.
- Remove the default throwing `service()` state and duplicated per-route
  method, `HEAD`, and coarse authorization handling.

## Completion Criteria

- Production dispatch and route tests use the same policy boundary.
- Tests cover buffered and streaming routes, `HEAD`, method rejection with an
  accurate `Allow` header, coarse authorization failure, and an after-flush
  action.
- Streaming handlers do not materialize response bodies in memory.
- The route table cannot drift from the method and authorization behavior that
  is actually executed.
