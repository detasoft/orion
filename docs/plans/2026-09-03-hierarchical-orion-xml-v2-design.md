# Hierarchical Orion XML v2 Design

## Goal

Replace the writable ACL-only XML document with a strictly validated, deterministic Orion configuration document
while retaining explicit read compatibility with legacy ACL v1 documents.

The first v2 slice defines stable hierarchy identities and the document envelope. Organization-local users, scoped
roles and grants, repository policies, mirrors, and configuration activation remain in their existing follow-up
tasks.

## Document Shape

The writable document has a lowercase root matching the task contract:

```xml
<orion schemaVersion="2">
  <system>
    <accessControl>...</accessControl>
  </system>
  <organizations>
    <organization id="acme">
      <displayName>Acme</displayName>
      <teams>
        <team id="platform">
          <displayName>Platform</displayName>
          <repositories>
            <repository id="api">
              <displayName>API</displayName>
            </repository>
          </repositories>
        </team>
      </teams>
    </organization>
  </organizations>
</orion>
```

The system access-control section carries the current global ACL without making the immutable domain model depend
on JAXB. Organization-local users remain inline under their owning organization when that follow-up is implemented;
v2 does not introduce external user files or include processing.

## Hierarchy and Identity

Organizations, teams, repositories, and users have stable identifiers distinct from mutable display names.
Changing a display name preserves identity. Changing an identifier is a delete-and-create operation; the initial
schema does not provide aliases or implicit rename tracking.

Identifiers are canonical lowercase ASCII segments. They start and end with an alphanumeric character and may use
single interior `-`, `_`, or `.` separators. Blank values, whitespace, uppercase aliases, empty segments, `.` and
`..`, slash, backslash, control characters, and non-canonical spellings are rejected rather than silently rewritten.
Sibling identifiers are unique within their parent after validation.

A repository belongs to exactly one team and organization because it is nested under both owners. Its canonical
address is `organization/team/repository`. An organization-local principal is `organization/user`; a root system
principal is `system/user`. Parsers accept only the exact number of canonical path segments, and scoped resolution
rejects cross-organization references.

## Domain and Wire Boundaries

The current model consists of immutable `OrionDocument`, hierarchy nodes, identifier value objects, repository and
principal addresses, and the existing immutable `AccessControl`. It contains no JAXB annotations.

JAXB DTOs live under a version-specific `schema.orion.v2` package. The v2 mapper is the only boundary between those
mutable wire objects and the current model. It validates required structure, identifiers, duplicate siblings, and
duplicate ACL user, role, and grant identifiers.

The v2 DTO owns an ACL wire representation instead of reusing the v1 DTO. This keeps the versions independent even
while the initial ACL fields have the same shape.

## Read and Write Flow

The reader securely inspects the root name and `schemaVersion` before unmarshalling:

- `<AccessControl>` with no version or version `1` uses the explicit v1 ACL translator and becomes an
  `OrionDocument` with that ACL in the system section and no organizations.
- `<orion schemaVersion="2">` is validated against the generated v2 XSD before JAXB unmarshalling and domain
  mapping.
- Unknown roots, missing v2 versions, unsupported versions, unknown elements or attributes, and malformed XML fail
  without fallback.

Only v2 is writable. Compatibility APIs that accept an `AccessControl` wrap it in an `OrionDocument`; they do not
serialize the legacy root.

The writer uses explicit JAXB property order and sorts every identifier-addressed collection. ACL users, roles,
grants, references, credentials, and grant expressions also receive stable ordering where their order has no domain
meaning. Repeated writes of equivalent models therefore produce identical UTF-8 XML suitable for Git diffs.

## Validation and Failure Behavior

Generated XSD validation enforces the structural wire contract and rejects unknown fields. Domain mapping enforces
semantic rules that generated JAXB schemas cannot express reliably, including canonical identifiers, duplicate
siblings, canonical addresses, and cross-scope references.

Read and write failures preserve their causes and identify whether root/version detection, schema validation,
unmarshalling, or semantic mapping failed. XML processing disables DTDs, external entities, and external schemas.

## Testing

Focused schema tests cover:

- legacy unversioned and explicit v1 reads;
- a complete v2 round trip with system ACL and nested hierarchy;
- immutable domain and version-specific DTO separation;
- deterministic output from differently ordered equivalent inputs;
- generated-schema rejection of unknown elements and attributes;
- duplicate organizations, teams, repositories, and ACL identifiers;
- invalid and escaping identifier/address forms;
- missing and unsupported versions and unknown roots.

The checked-in example configuration is migrated only if it exists in the current repository layout. Runtime
snapshot publication and preservation of non-ACL sections during administrative ACL updates remain owned by the
native configuration snapshot and administration tasks.
