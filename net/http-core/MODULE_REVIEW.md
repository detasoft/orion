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

## 16. Forwarded IPv6 hosts suppress the advertised port

**Problem.** A trusted proxy forwarding `[2001:db8::1]` as host, `8443` as port and HTTPS produces a packfile
base URI without port 8443. The resolver treats any colon in the host as an explicit port, including IPv6
address colons; advertised pack downloads target the wrong endpoint.

**Sources.** [Port detection](src/main/java/pro/deta/orion/transport/http/OrionGitPackfileUriBaseResolver.java#L187),
[Git route consumer](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java),
[resolver tests](src/test/java/pro/deta/orion/transport/http/OrionGitPackfileUriBaseResolverTest.java#L82),
and [advertisement/download contract](src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java#L89).

**Documented behavior and contract.** Trusted proxy headers supply the externally reachable URI when the
automatic base URI is configured. An explicit host port takes precedence; an IPv6 address colon is not a port.
Untrusted-header rejection and default-port omission remain required.

**Minimal repair.** Detect a port after the closing bracket of a bracketed IPv6 host. Extend existing resolver
tests for IPv6 with a forwarded port, an explicit port, default ports and ordinary hostnames.

**Alternatives and consequences.** A manually configured base URI avoids the affected path but narrows
automatic proxy support. A new URI abstraction is unnecessary; local parsing preserves the current API.

**Confidence.** High for the concrete input and real consumer; no live proxy reproduction was run.

**Priority signals.** Importance: medium, downloads fail for this deployment. Repair ease: high, local parsing
and existing tests; this changes observable behavior.
