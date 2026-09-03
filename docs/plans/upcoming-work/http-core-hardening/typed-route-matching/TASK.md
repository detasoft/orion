# Centralize Typed HTTP Route Matching

Status: todo
Source: finding 3 in [the module review](../../../../../net/http-core/MODULE_REVIEW.md)
Depends on: [unified route invocation](../unified-route-invocation/TASK.md)

Replace unrestricted character wildcards and handler-local path parsing with
one segment-aware routing result shared by dispatch and handlers.

## Scope

- Normalize the servlet path, including context-path handling, exactly once at
  the HTTP adapter boundary.
- Support only the route forms required by the application: exact paths,
  segment-bound prefixes or templates, and an explicit frontend fallback.
- Return an immutable match containing the selected route plus typed captures
  or a normalized unmatched remainder; handlers must not parse the servlet
  path a second time.
- Reject duplicate and ambiguous route registrations during registry
  construction instead of resolving them by pattern spelling length.
- Give the `/r/` namespace one registered owner that deliberately dispatches
  Smart HTTP and published-pack endpoints from the same parsed repository
  path.
- Model `/session-host` and its target path with segment boundaries, and model
  the ACME challenge token as a path capture.
- Delete `WildcardMatcher` and the duplicated `routePath`, prefix stripping,
  and equivalent path-recovery helpers once callers use the routing result.

## Completion Criteria

- `/session-hostile` does not select the session-host download route.
- Nested repository paths and packfile endpoints are parsed without relying on
  overlapping wildcard precedence.
- Tests cover exact and parameterized matches, frontend fallback, context-path
  mounting, malformed paths, and startup rejection of ambiguous definitions.
- Direct handler tests receive the same parsed route values as production
  dispatch.
- No general `*` matcher or handler-local servlet-path normalization remains.
