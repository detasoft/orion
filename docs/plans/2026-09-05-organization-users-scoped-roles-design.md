# Organization Users and Scoped Roles Design

Status: approved on 2026-09-05.

## Goal

Extend the immutable Orion configuration and XML v2 wire format with
organization-local users and locally owned roles and grants at organization,
team, and repository scope. Keep system administration and recovery in the
existing system access-control section.

This slice defines and validates desired configuration. Runtime authentication,
configuration activation, administrative mutation, and access-decision
evaluation remain in their existing follow-up tasks.

## Ownership Model

Users belong directly to one organization. The same user id may exist in
different organizations; its canonical principal address is
`organization/user`. System users retain `system/user` addresses and never
become organization users implicitly.

Organizations own users, roles, and grants. Teams and repositories own roles
and grants. These definitions are nested under their owners in the immutable
domain model and in `orion.xml`; there is no second global registry keyed by
scope strings.

Each user has a stable `UserId`, optional profile fields, an explicit enabled
state, credentials, team memberships, and role assignments. Membership is a
relationship to a team in the same organization and grants no permission by
itself.

## Credentials

The organization user model stores password verifiers and canonical OpenSSH
public keys directly because neither is a recoverable secret. Credential types,
required values, and public-key syntax are validated at the schema boundary.
Reversible confidential credential material is not accepted as plaintext; a
future such credential must use the existing validated encrypted configuration
value mechanism.

Credential ordering has no meaning. Canonical output sorts credentials by
type, key id, and value. Rotation replaces explicit credential entries in a
configuration update; the model contains no implicit current or fallback
credential path.

## Scoped Roles and Grants

`RoleId` and `GrantId` are canonical identifier segments. A role or grant
address appends its local id to the owning scope:

- organization: `acme/developer`;
- team: `acme/platform/developer`;
- repository: `acme/platform/api/developer`.

Role and grant references use distinct typed addresses even when their textual
forms are equal. Users may be assigned roles anywhere inside their own
organization. A role may include roles and grants from its own scope or an
ancestor scope. References to descendants or another organization are invalid.

A grant owns an explicit `ALLOW` or `DENY` effect and the current typed grant
expressions. This slice persists the information needed for later hierarchical
authorization but does not choose the final allow/deny precedence algorithm.

Role composition is validated as a graph. Missing references, references that
escape the permitted scope, and cycles fail the complete document rather than
being ignored or deferred.

## XML Shape and Mapping

The v2 wire model adds users, roles, and grants to each supported owner while
keeping all fields version-specific and mutable only at the JAXB boundary. A
representative shape is:

```xml
<organization id="acme">
  <users>
    <user id="alice" enabled="true">
      <memberships><team>platform</team></memberships>
      <roles><role>acme/developer</role></roles>
      <credentials>...</credentials>
    </user>
  </users>
  <grants>...</grants>
  <roles>...</roles>
  <teams>
    <team id="platform">
      <grants>...</grants>
      <roles>...</roles>
      <repositories>
        <repository id="api">
          <grants>...</grants>
          <roles>...</roles>
        </repository>
      </repositories>
    </team>
  </teams>
</organization>
```

The mapper sorts every identifier-addressed collection and every semantically
unordered reference list. Equivalent domain documents therefore retain
byte-identical canonical XML output.

## Legacy Boundary

Legacy ACL v1 input and the v2 system access-control section continue to map to
system users, roles, and grants. Orion cannot infer an organization for those
identities, so it performs no automatic migration. New organization-local
entries use only the new nested model; no aliases, dual reads, or shadow copies
are introduced.

## Validation and Failure Behavior

Construction and XML mapping reject duplicate local ids, invalid identifiers,
duplicate credentials, missing teams, missing role or grant targets,
cross-organization references, downward role imports, and role cycles. The
reader reports semantic mapping failure with the offending address and retains
the previous active configuration when activation is implemented later.

An enabled user must have a valid identity but does not need a credential;
credential-less service or externally authenticated identities remain
representable. Disabled users remain in configuration and round-trip normally.

## Testing

Focused schema tests cover immutable copies, enabled and disabled users,
duplicate ids, equal user and role ids in separate organizations, memberships,
all three definition scopes, legal ancestor references, missing and escaping
references, cycles, credential validation, and deterministic XML round trips.

Legacy v1 and v2 system ACL fixtures remain unchanged. Tests assert that
organization users do not appear in the system ACL projection and that writing
the extended document preserves every existing hierarchy, repository, remote,
and system access-control field.
