# Admin ACL XML Schema Versioning

## Current Contract

The admin ACL XML contract is generated from the JAXB annotations on
`pro.deta.orion.acl.schema.AccessControl`.

`GET /schemas/orion-admin-acl.xsd` exposes the current editor schema.
`POST /schemas/orion-admin-acl.xsd` validates a submitted XML document against
that schema and returns a JSON validation result.

Runtime parsing also accepts the old repeated-plural collection element shape,
such as `<users><users>...</users></users>`, but that shape is a compatibility
quirk and is not part of the published schema.

## When a New Version Is Needed

First decide whether the change is compatible with the current schema.

Compatible changes can stay on the current model when old files still parse
without a migration step. Add or extend tests for generated XML, generated XSD,
and reading files that omit the new data.

For a breaking change, use this sequence:

1. Freeze the previous XML contract before mutating it.
   Create a versioned DTO package, for example
   `pro.deta.orion.acl.schema.v1`, and copy the previous JAXB annotations there.
   Do not generate old schemas from the new DTO.

2. Keep the old schema immutable.
   Add a versioned schema generator for the frozen DTO and expose it at an
   immutable URL such as `/schemas/orion-admin-acl-v1.xsd`.

3. Keep `/schemas/orion-admin-acl.xsd` as the current alias.
   The unversioned route should always return the latest schema intended for
   editors.

4. Add an explicit version marker to the new XML format.
   Prefer a root attribute such as `schemaVersion="2"` or a namespace. Treat an
   absent marker as the oldest supported version.

5. Dispatch reads by version.
   The reader should detect the version before JAXB unmarshalling, unmarshal
   with the matching DTO, then map that DTO into the current runtime
   `AccessControl` model.

6. Keep writing current by default.
   Add an explicit writer API for an older version only when the product needs
   to export older files.

7. Test each supported version with fixtures.
   Cover old fixture to current runtime model, current runtime XML to current
   XSD, each immutable schema endpoint, and rejection of XML that belongs to a
   different version.

Frozen version DTOs should only change for parser safety, dependency migration,
or tests. Runtime behavior changes belong in the mapper from the frozen DTO to
the current domain model.
