# Read-Only Domain Commands Design

## Goal

Expose Orion's operator-facing read model through the existing SSH command tree without coupling command handlers
to Apache Mina SSHD or pretending that unfinished runtime services are available.

The first production-backed commands are `whoami`, repository inspection, JVM resource visibility, and lifecycle
service visibility. Organization, session, and proxy command shapes are present behind typed domain sources and
return an explicit unavailable result until their owning runtime services provide query adapters.

## Approaches Considered

### Direct dependencies from one command catalog

The catalog could inject every current repository, lifecycle, configuration, session, and synchronization class.
This is initially direct, but it couples the SSH module to unfinished subsystems, makes availability implicit, and
encourages commands to reconstruct domain state from storage implementation details.

### Infer missing domains from files and internal stores

Commands could scan ACL XML, journal directories, or Git synchronization state. That would produce plausible output
before the owning services are ready, but it would bypass their consistency and authorization contracts. Empty or
partially written storage would also be indistinguishable from a genuinely empty domain.

### Typed operator read source with explicit availability

This is the selected approach. A Mina-independent source exposes immutable domain views and typed
available/unavailable/failure results. The command catalog performs scoped resolution, authorization, and rendering.
The default source uses only established runtime APIs; unfinished domains remain explicitly unavailable and receive
real adapters in their owning task trees.

## Boundaries

- Command handlers receive immutable views, `CommandInvocation`, and Orion authorization rules, never Mina sessions
  or terminal streams.
- Sources report domain facts but do not decide whether the caller may see them.
- Filtering happens before rows, counts, candidates, completion, and ambiguity results are constructed.
- A denied exact selector is indistinguishable from a missing resource.
- A backend outage is distinct from an empty collection and from a missing resource.
- Handlers return `Rows`, `ObjectValue`, or a stable failure; presentation remains in existing renderers.
- Renderers encode control characters and backslashes in structured row/object fields so source values cannot forge
  records or inject terminal control sequences; intentionally multi-line legacy messages keep their existing form.
- The task adds no filtering grammar, pagination, JSON output, streaming, or session attachment.

## Query Contracts

Add a small operator query package in `net/git-transport`, separate from the command catalog. Its sealed result has:

- `AvailableSnapshot<T>` with a defensively copied immutable list;
- `AvailableValue<T>` restricted to the immutable scalar-value contract used by system resources;
- `Unavailable` with a stable source identifier;
- `Failed` with a source identifier and cause retained for diagnostics but not rendered to users.

Every query-result variant has metadata-only diagnostic text; `toString()` never reveals values, hidden collection
sizes, exception details, or other pre-ACL source state.

Immutable views cover repositories, organizations and their users/repositories, sessions, proxies, system resources,
and lifecycle services. IDs are canonical path selectors and are validated as parser-addressable single segments.
Optional display names are aliases only; they never replace IDs and are accepted only when they are safe single path
segments. Views carry only the ownership or repository association needed by authorization.

The default implementation provides:

- repositories from `NativeGitRepositoryProvider`, including canonical name, default head, and ref count;
- system resources from a narrow runtime-metrics capability;
- recursive lifecycle service status from `AggregateStateMachine.status()`, paired with exact direct-child machine
  lookup rather than recursive name lookup;
- explicit unavailable results for organization, session, and proxy sources until those services are wired.

The runtime-metrics capability is injectable so tests do not assert machine-specific values.

## Command Tree

Compose a dedicated read-only catalog into the existing root:

```text
/
|-- whoami
|-- repository
|   |-- ls
|   `-- <id-or-name> show
|-- organization
|   |-- ls
|   `-- <id-or-name>
|       |-- user ls
|       `-- repository ls
|-- session
|   |-- ls
|   `-- <id-or-name> show
|-- proxy
|   `-- ls
`-- system
    |-- resource
    `-- service ls
```

All collection results are sorted by canonical ID. Object fields and row columns have fixed order. `whoami` returns
the authenticated user ID. Repository rows expose `id`, `name`, `defaultHead`, and `refCount`. System resource output
uses byte counts and processor count. Lifecycle rows expose the canonical hierarchical service path, current state,
computed state, and terminal flag.

## Scoped Resolution and Availability

Extend scoped resource catalogs so candidate lookup can report available, unavailable, access denied, or failed
rather than only a list. Propagate those states through `ScopedResourceResolver`, `CommandNavigator`,
`DefaultCommandDispatcher`, and interactive path-only navigation. Static and dynamic commands therefore return the
same stable failure when a source is absent, access is rejected before resolution, or lookup fails.

Resolution order stays unchanged: exact canonical ID, unique visible ID prefix, then exact visible display name.
Ambiguity returns only canonical IDs that the current user is allowed to see. Navigation and completion use the same
filtered source and cannot reveal hidden names or counts.

## Authorization

Every command first requires an authenticated named user.

- `whoami` may read only the current identity.
- Repository list, show, and organization-local repository list use `RepositoryAccessRules.read()` per concrete
  repository. Global and scoped collections contain only readable repositories.
- An organization is visible only when the user is an application administrator, is a member represented by that
  organization's user view, or can read at least one repository in that organization.
- Organization-local users are visible only to application administrators or as the caller's own user entry.
- Session views are visible to application administrators, their owner, or callers with read access to the
  associated repository. Proxy views are repository-scoped rather than user-owned: they are visible to application
  administrators or callers with read access to the associated repository; a proxy without a repository association
  is visible only to application administrators.
- System resource and lifecycle service commands require application-admin access.

Exact lookup applies the same rule as listing. Source failures and access denials are sanitized before rendering and
auditing.

## Composition and Compatibility

`LegacySshCommandCatalog` remains the compatibility root for token and administrative aliases. It composes the new
catalog alongside `/auth/key`; the existing `state`, `status`, and `repositories` aliases keep their current output
and authorization during this task.

Both SSH exec and interactive shell already share the same dispatcher and command tree. No frontend-specific command
implementation is added. Tests verify parity by dispatching the same request under plain and terminal presentation
and by checking navigator visibility/completion.

## Error Handling

- Empty available sources produce successful empty rows.
- Missing or denied resources produce `MISSING_RESOURCE`.
- Multiple visible matches produce `AMBIGUOUS_RESOURCE` with visible canonical IDs only.
- An absent source produces `SERVICE_UNAVAILABLE` with a stable generic message.
- An unexpected source failure produces `HANDLER_FAILED`; its cause is not included in the result, audit payload, or
  terminal output.

## Verification

Focused tests cover result immutability, source availability, repository and lifecycle adapters, every command shape,
stable field order, per-item ACL filtering, cross-organization collisions, self/admin visibility, missing and
ambiguous selectors, unavailable and failed sources, completion leakage, and exec/interactive result parity.

The implementation worker runs the focused command, authorization, lifecycle, and transport suites plus
`mvn verify -Pdev -T 4`. The final squashed commit is followed by `make test` under the repository workflow.
