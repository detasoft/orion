# ACME and HTTPS Material Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the protected PKCS12 store the only owner of ACME and HTTPS key material, while moving all HTTPS
desired-state configuration into the versioned `orion.xml` document.

**Architecture:** A versioned Orion XML v2 document contains ACL state, HTTPS listener settings, ACME policy, and
purpose-scoped material references. One closeable material owner opens the store once and derives narrow server
identity, ACME, and TLS capabilities. Jetty receives an in-memory `SSLContext`; normal HTTP APIs never receive a
private key, and TLS sends the leaf and intermediate certificates without the root trust anchor.

**Tech Stack:** Java 21, JAXB, Dagger, Java `KeyStore`/PKCS12 and JSSE, Jetty 12, acme4j, JUnit 5, AssertJ.

---

## Fixed decisions

- Process bootstrap YAML/TOML retains only values needed before `orion.xml` can be read: the configuration
  repository locator/ref, material-store location and credential reference, cluster identity, and unrelated
  settings not migrated by this task.
- Remove the complete HTTPS desired-state subtree from bootstrap YAML/TOML. Its listener address, port, enabled
  state, public URL, ACME policy, domains, timeouts, and material references live in `orion.xml` only.
- Introduce an Orion XML v2 document rooted at `Orion`; do not add HTTPS fields to the v1 `AccessControl` root.
  Legacy ACL v1 remains readable as a document with HTTPS absent. It never activates old TOML HTTPS settings.
- The XML document stores only aliases and versions. It never contains private keys, certificates, passwords, or
  material-store paths. ACME account and HTTPS identity material are cluster-scoped and purpose-validated.
- One `TLS_IDENTITY` entry atomically owns the domain private key, leaf certificate, and intermediate chain.
  Do not model the leaf and intermediates as independent aliases.
- Add a distinct `ACME_ACCOUNT` purpose for the ACME account key. ACME key generation is lazy; loading a document
  with ACME disabled must not generate unused material.
- A successful ACME issuance always validates, installs, and durably saves the issued chain with its domain key.
  Remove the request-level `persist` switch and all legacy path fallback/import behavior.
- Model a public server issuer trust anchor separately from the server identity. It is an optional typed trusted
  certificate reference used to validate an internal-CA chain and support later public root distribution. Jetty
  does not append or send this root in the TLS handshake.
- Model mTLS client trust independently from the server issuer. HTTPS client authentication is `disabled`, `want`,
  or `required` and refers to one or more typed client trust anchors. Both roles may deliberately reference the
  same root alias, but the configuration roles remain distinct.
- Public WebPKI ACME normally omits the server issuer trust anchor because clients already possess suitable roots.
- Jetty receives a ready `SSLContext` from a typed TLS capability through its API. Remove PEM/JKS reads, temporary
  stores owned by the HTTP module, automatic self-signed fallback, and raw `KeyMaterialService` injection.
- Never serve a PKCS12 storage-only certificate as an HTTPS identity. With a fresh store, issue over enabled HTTP
  while HTTPS is disabled, then enable/restart HTTPS. HTTPS-only startup without a usable issued or provisioned
  identity fails closed. Certificate activation may require restart in this first implementation.
- Ordinary certificate issue/download responses contain only safe metadata and certificate-chain PEM. A future
  private-key export requires a separate privileged capability and endpoint or command.
- The material owner is opened once and closed once by `App`. Dagger receives only narrow capabilities, never the
  aggregate owner or raw material service.

### Task 1: Define the Orion XML v2 desired-state document

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionDocument.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionMaterialReference.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionHttpsConfiguration.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionAcmeConfiguration.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXml.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXmlSchemaVersion.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java`
- Test: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionXmlTest.java`
- Modify: `orion/orion.xml`

**Steps:**

1. Write schema tests for an `Orion` v2 document containing existing ACL data and a complete HTTPS subtree. Cover
   identity alias/version, optional server issuer trust anchor, ACME account alias/version and policy, and separate
   mTLS mode/client trust-anchor references.
2. Run `make run-test MODULE=core/schema TEST='pro.deta.orion.schema.orion.OrionXmlTest'` and confirm the new XML
   API is absent or the v2 document cannot be parsed.
3. Add immutable current-domain types. Reject blank aliases, non-positive versions, invalid ports, duplicate trust
   anchors, client-auth modes without roots, and ACME enabled without account material or domains.
4. Add JAXB v2 wire types and an explicit mapper. Keep wire mutability out of the current-domain model.
5. Make `OrionXml` detect legacy `<AccessControl schemaVersion="1">`, delegate to the existing ACL translator, and
   return a document with HTTPS absent. Unknown roots and schema versions must fail instead of falling back.
6. Update the checked-in `orion/orion.xml` example to v2 while preserving its ACL semantics. Include HTTPS disabled
   by default and documented internal-CA and mTLS examples without certificate bytes.
7. Run the focused schema test again and commit the schema slice.

### Task 2: Load and preserve the whole versioned document

**Files:**

- Create: `core/common/src/main/java/pro/deta/orion/config/OrionDesiredState.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/XmlService.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/storage/AccessControlSnapshot.java`
- Modify: `connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`

**Steps:**

1. Add failing tests proving startup publishes the full v2 document before transports start, a legacy v1 document
   publishes equivalent ACL with HTTPS absent, and an ACL write preserves the current HTTPS subtree semantically.
2. Add reload tests proving malformed or unsupported XML leaves the last valid document and ACL active.
3. Run the focused ACL and bootstrap lifecycle tests and confirm the current ACL-only serializer loses or cannot
   expose HTTPS state.
4. Introduce a narrow immutable `OrionDesiredState` projection/provider that exposes the active document and its
   revision. Do not expose the mutable bootstrap `OrionConfiguration` as desired state.
5. Evolve `XmlService` and the ACL service to deserialize one Orion document, publish it before transport startup,
   project ACL from it, and preserve every non-ACL section during ACL updates.
6. Keep native Git commit identity as the document revision. Do not introduce a second configuration file or
   independent HTTPS reload source.
7. Document and test that listener/certificate changes become effective on restart in this first implementation;
   in-process ACL reload must not partially mutate a running Jetty connector.
8. Run the focused tests again and commit the versioned-document activation slice.

### Task 3: Add typed ACME and trust-anchor material capabilities

**Files:**

- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialPurpose.java`
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/TrustedCertificateDescriptor.java`
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/AcmeKeyMaterialCapability.java`
- Create or modify: the TLS server capability under
  `core/key-material/src/main/java/pro/deta/orion/keymaterial/`
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialService.java`
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialCapabilities.java`
- Test: `core/key-material/src/test/java/pro/deta/orion/keymaterial/KeyMaterialCapabilitiesTest.java`
- Test: `core/key-material/src/test/java/pro/deta/orion/keymaterial/KeyMaterialServiceTest.java`

**Steps:**

1. Add failing tests for lazy ACME account/domain generation, existing-entry reuse, wrong purpose/version/scope/
   algorithm/entry type, one durable save after creating both keys, and no partial save after failure.
2. Add failing chain tests for key/leaf mismatch, broken issuer signatures, non-X.509 members, an optional mismatched
   issuer root, a valid chain whose root is retained separately, and reopen after successful installation.
3. Add failing TLS-context tests proving the key manager exposes leaf plus intermediates but not the configured root,
   and proving disabled/want/required client authentication uses only the client trust-anchor role.
4. Run the focused key-material tests and confirm failures describe the missing typed behavior.
5. Add `ACME_ACCOUNT` and the smallest trust-anchor purpose/descriptor needed for typed `TrustedCertificateEntry`
   metadata. Do not treat a storage certificate as a trust anchor.
6. Add typed trusted-certificate set/get/validation inside the service boundary, including alias, purpose, algorithm,
   version, and scope metadata. Preserve untyped compatibility methods only if another production caller needs them;
   do not expose them through Dagger.
7. Implement the ACME capability with only account/domain key acquisition, chain installation, public-chain read,
   and required durable coordination. It may accept typed descriptors from desired state but must reject every
   non-ACME/non-TLS purpose.
8. Evolve the TLS capability to build a server `SSLContext` from one identity, an optional issuer trust anchor, and
   optional client trust anchors. Return public certificate/root data only where the contract needs it.
9. Run:

   ```bash
   make run-test \
     MODULE=core/key-material \
     TEST='pro.deta.orion.keymaterial.KeyMaterialCapabilitiesTest,pro.deta.orion.keymaterial.KeyMaterialServiceTest'
   ```

   Commit the typed-capability slice.

### Task 4: Open one aggregate material owner and bind only capabilities

**Files:**

- Create or replace: the aggregate material owner under
  `core/key-material/src/main/java/pro/deta/orion/keymaterial/`
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/ServerIdentityMaterial.java`
- Test: the matching owner test under `core/key-material/src/test/java/pro/deta/orion/keymaterial/`
- Modify or replace: `core/bootstrap/src/main/java/pro/deta/orion/ServerIdentityMaterialFactory.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/App.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionComponent.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/ServerIdentityMaterialFactoryTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/OrionStartupIT.java`

**Steps:**

1. Add failing owner/bootstrap tests proving one store instance derives server identity, ACME, and TLS capabilities;
   closes once; and never publishes the owner or raw service through the component.
2. Add tests proving purpose-scoped capabilities can validate references from the later-loaded desired state without
   requiring aliases to remain in bootstrap configuration. Missing ACME entries remain lazy; an enabled HTTPS
   identity must already have a non-storage certificate chain.
3. Run the focused owner and bootstrap tests and confirm the server-identity-only factory cannot meet the contract.
4. Replace the server-identity-only closeable with one aggregate owner. Keep server-signing bootstrap behavior intact,
   but derive ACME and TLS capabilities from the same service.
5. Bind only `ServerIdentityCapability`, `AcmeKeyMaterialCapability`, and the narrow TLS capability into Dagger.
   Add explicit unavailable test implementations where a component test does not exercise the capability.
6. Make `App` open and close the aggregate exactly once around the runtime lifecycle. Preserve bootstrap failure
   handling and never hand the aggregate owner to runtime services.
7. Run focused bootstrap tests, commit, then run `make test` as required after the implementation commit.

### Task 5: Remove HTTPS and ACME desired state from bootstrap configuration

**Files:**

- Modify: `core/schema/src/main/java/pro/deta/orion/schema/config/OrionConfiguration.java`
- Delete: `core/schema/src/main/java/pro/deta/orion/schema/config/HttpsTransportConfig.java`
- Delete: `core/schema/src/main/java/pro/deta/orion/schema/config/SSLKeyStoreConfig.java`
- Delete: `core/schema/src/main/java/pro/deta/orion/schema/config/AcmeConfig.java`
- Modify: `core/bootstrap/src/main/resources/config.yml`
- Modify: `core/bootstrap/src/main/resources/config.toml`
- Modify: `connectors/configuration-location/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java`

**Steps:**

1. Change the bootstrap-shape test to assert only bootstrap/unmigrated settings load from YAML and that an HTTPS
   subtree, legacy key paths, or keystore path cannot become active configuration.
2. Run the focused configuration-location test and confirm the old shape is still accepted.
3. Remove HTTPS from `OrionConfiguration.AppTransport`, delete the legacy HTTPS/ACME path models when no caller
   remains, and remove both sample HTTPS sections. Do not move material aliases into `KeyMaterialConfig`.
4. Prefer an explicit unknown/legacy-field validation failure where the loader supports it; at minimum prove
   production code has no getter or fallback capable of activating the old values.
5. Run `make run-test MODULE=connectors/configuration-location TEST='pro.deta.orion.config.OrionConfigurationBootstrapShapeTest'`
   and commit the bootstrap-boundary slice.

### Task 6: Migrate ACME issuance to the material capability

**Files:**

- Modify: `net/http-core/pom.xml`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/AcmeCertificateService.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/AcmeCertificateIssuer.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/Acme4jClient.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/AcmeCertificateIssueRequest.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/IssuedAcmeCertificate.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/AcmeCertificateIssuerTest.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/AcmeCertificateServiceTest.java`

**Steps:**

1. Add failing tests showing issuance reads XML desired state, lazily obtains capability-owned account/domain keys,
   installs the returned chain, saves once, survives store reopen, and creates no legacy files.
2. Add a failure test proving chain validation or persistence failure does not report success or expose partially
   installed material.
3. Run the focused ACME tests and confirm they fail against filesystem ownership.
4. Change the acme4j boundary to retain an ordered `List<X509Certificate>` for persistence. PEM encoding is an HTTP
   output concern, not the stored domain representation.
5. Remove path resolution, `Files` access, key generation, private-key serialization, `persist`, and nginx output
   from the service. Read ACME policy/material references only from the active Orion document.
6. Reduce `IssuedAcmeCertificate` to domains, safe certificate metadata, and the public chain. Never include a
   private key accessor or combined PEM method.
7. Add the direct `core/key-material` dependency only for the narrow capability contract.
8. Run the focused tests again and commit the ACME migration slice.

### Task 7: Supply Jetty TLS and optional mTLS through the capability API

**Files:**

- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/JettyHTTPServerTest.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/ACMECertificateChallengeTest.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachineTest.java`
- Modify: `net/transport/src/test/java/pro/deta/orion/transport/TransportLifecycleStateMachineTest.java`

**Steps:**

1. Add a failing HTTPS test using an identity from an in-memory material store and complete a TLS request without
   creating a PEM/JKS file. Inspect the served chain and assert it contains leaf/intermediates but no root.
2. Add failing mTLS tests for disabled, want, and required modes with a trusted client CA and an untrusted client.
   Assert server-issuer trust is not accepted as client trust unless both roles reference it.
3. Add a failing startup test for HTTPS enabled with missing identity or a storage-only certificate. HTTP-only ACME
   issuance remains possible when HTTPS is disabled.
4. Run the focused Jetty/challenge tests and confirm current path/self-signed behavior violates the contract.
5. Inject active desired state and the narrow TLS capability. Build `SslContextFactory.Server` with
   `setSslContext`; apply need/want client-auth flags explicitly.
6. Delete JKS/PEM reads, password handling, generated Jetty aliases, and self-signed fallback from production HTTP
   code. Keep HTTP connector startup independent when HTTPS is absent/disabled.
7. Run the focused HTTP and transport tests again and commit the Jetty migration slice.

### Task 8: Remove private-key HTTP output and verify the boundary

**Files:**

- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionAdminAcmeCertificateRoute.java`
- Modify or create: the matching route test under
  `net/http-core/src/test/java/pro/deta/orion/transport/http/`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionConfigurationJsonSchema.java`
- Modify: relevant operator/configuration documentation that advertises HTTPS paths or nginx PEM
- Modify: affected component/startup test support

**Steps:**

1. Add failing GET/POST route tests for certificate-chain PEM and safe metadata, `Cache-Control: no-store`, neutral
   certificate filenames, and absence of private-key bytes.
2. Update routes so GET and POST return public chain/metadata only. Remove nginx-specific filenames and the implicit
   combined-PEM download without adding a replacement private export.
3. Search production source and samples for `accountKeyPath`, `domainKeyPath`, `certificatePath`,
   `privateKeyPem`, `nginxPem`, `SSLKeyStoreConfig`, ACME `Files` access, and Jetty self-signed fallback.
   Remove every active owner.
4. Verify the runtime component exposes no raw material service/owner and that server issuer roots and client trust
   roots remain role-separated.
5. Run all affected focused tests through `make run-test`, then run `git diff --check`.
6. Run `mvn verify -Pdev -T 4` in the dedicated task worktree and inspect the complete result.
7. Commit any final implementation/docs slice and run `make test` after the commit as required by `AGENTS.md`.
8. Return the unsquashed branch for independent review. Do not integrate into `main` until the mandatory user gate.
