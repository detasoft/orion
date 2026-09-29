# Module Review: `http-core`

## 15. XML exports lose characters through the servlet writer

**Problem.** ACL export decodes UTF-8 XML bytes to a string and sends `application/xml` through a servlet
writer without setting its charset. With the pinned Jetty default, non-Latin text is replaced or encoded
as ISO-8859-1 despite the XML declaration specifying UTF-8. Export followed by import can corrupt identities.

**Sources.** [ACL export](src/main/java/pro/deta/orion/transport/http/OrionAdminAccessControlRoute.java#L28),
[writer](src/main/java/pro/deta/orion/transport/http/OrionHttpResponseWriter.java#L59),
[XML translation](../../core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXmlV2Translator.java#L40),
and [configuration export](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java).
[Real HTTP round trip](../../tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpAdminAclUpdateIT.java)
uses the exported body for import. [Admin HTTP test](../../tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpAdminApiIT.java)
uses ASCII; [unified route test](src/test/java/pro/deta/orion/transport/http/OrionHttpUnifiedRouteTest.java#L369)
provides a UTF-8 writer and masks the container default. Jetty 12.0.12's MIME encoding mapping supplies no
XML charset and its servlet writer falls back to ISO-8859-1.

**Documented behavior and contract.** The XML translator declares UTF-8; ACL configuration download and
upload must preserve valid configured text. No ASCII-only identity restriction was found. Authorization,
ETag and optimistic update behavior remain required.

**Minimal repair.** Return the original bytes through the existing resource response with XML content type.
Remove the unnecessary decode/re-encode. Cover actual HTTP export/import with accented and non-Latin text
and parse the returned bytes according to their XML declaration.

**Alternatives and consequences.** Setting a global writer charset changes unrelated response behavior.
A local byte response preserves the existing representation without a new abstraction or persistence change.

**Confidence.** High from the production path and pinned container implementation; no runtime reproduction
was run during the audit.

**Priority signals.** Importance: high, configuration export can lose identity data. Repair ease: high,
a local existing-path substitution with meaningful HTTP coverage.
