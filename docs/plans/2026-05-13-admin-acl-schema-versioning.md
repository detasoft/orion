# Admin ACL XML Schema Versioning

## Current Contract

The runtime ACL model is `pro.deta.orion.acl.schema.AccessControl`.
The XML/XSD contract is a separate DTO in a versioned schema package,
currently `pro.deta.orion.acl.schema.v1.AccessControlV1`.

The admin ACL XML contract is generated from the JAXB annotations on the
latest versioned schema DTO, not from the runtime model.
Current files written by Orion include the root attribute `schemaVersion="1"`.
Files without that attribute are treated as version 1 for compatibility.

`GET /schemas/orion-admin-acl.xsd` exposes the current editor schema.
`POST /schemas/orion-admin-acl.xsd` validates a submitted XML document against
that schema and returns a JSON validation result.

Runtime parsing also accepts the old repeated-plural collection element shape,
such as `<users><users>...</users></users>`, but that shape is a compatibility
quirk and is not part of the published schema.

All runtime reads go through a schema-version dispatcher. The selected schema
DTO is mapped into the current runtime `AccessControl` representation before the
rest of the application sees it. Runtime writes always serialize the current
runtime model through the latest schema DTO only.

## When a New Version Is Needed

First decide whether the change is compatible with the current schema.

Compatible changes can stay on the current model when old files still parse
without a migration step. Add or extend tests for generated XML, generated XSD,
and reading files that omit the new data.

For a breaking change, use this sequence:

1. Freeze the previous XML contract before mutating it.
   Keep the existing versioned DTO package, for example
   `pro.deta.orion.acl.schema.v1`, immutable. Do not generate old schemas from
   the new DTO.

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
   The reader must detect the version before JAXB unmarshalling, unmarshal with
   the matching DTO, then map that DTO into the current runtime `AccessControl`
   model. The rest of the application should only depend on the current runtime
   model.

6. Keep writing latest only.
   Runtime save paths must serialize through the latest DTO. Do not add an older
   writer unless there is a separate export feature with explicit version
   selection.

7. Test each supported version with fixtures.
   Cover old fixture to current runtime model, current runtime XML to current
   XSD, each immutable schema endpoint, and rejection of XML that belongs to a
   different version.

Frozen version DTOs should only change for parser safety, dependency migration,
or tests. Runtime behavior changes belong in the mapper from the frozen DTO to
the current domain model.
