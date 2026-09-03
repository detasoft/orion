# ACME Key Material Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the protected PKCS12 material store the sole runtime owner of ACME account and domain keys while
removing private keys from ordinary HTTP results.

**Architecture:** One closeable bootstrap owner opens the material store once and derives purpose-scoped server
identity and ACME capabilities. ACME settings retain CA, domain, policy, and timeout configuration, but refer to
material aliases rather than files. Successful issuance installs the X.509 chain on the configured TLS identity;
HTTP can read certificate data but cannot obtain or serialize its private key.

**Tech Stack:** Java 21, Dagger, Java `KeyStore`/PKCS12, acme4j, Jackson, Jetty, JUnit 5, AssertJ.

---

## Fixed decisions

- The tracked ACME keys are disclosed test artifacts. Delete them; do not migrate their bytes.
- Current-tree deletion does not retract the material from Git history. History cleanup and key or certificate
  rotation remain separate operator security decisions.
- The material store is opened once. Do not create a second `KeyMaterialService` over the same backing store for
  ACME.
- ACME account material uses a distinct `ACME_ACCOUNT` purpose. The domain key and its issued certificate chain
  use `TLS_IDENTITY`.
- Both descriptors use the configured cluster scope. Node-local ACME keys require a later explicit deployment
  requirement.
- Key generation is lazy when issuance first needs the configured aliases; disabled ACME configuration must not
  create unused keys.
- A successful issuance always installs and saves the returned chain with the domain key. The old request-level
  `persist` switch is removed.
- Normal certificate issue and download responses contain no private-key PEM. A future operator export is a
  separate privileged capability and endpoint or command, not part of this migration.
- Legacy ACME path fields are removed from active configuration without a silent file fallback. Any real
  installation import must be an explicit operator action and must never import the disclosed repository files.

### Task 1: Remove source-tree ACME material

**Files:**

- Delete: `net/http-core/account.keypair`
- Delete: `net/http-core/domain.keypair`
- Delete: `net/http-core/domain.crt`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/ACMECertificateChallengeTest.java`
- Modify: `.gitignore`

**Steps:**

1. Remove the disabled public-CA method, relative output constants, and key-file helper while retaining the
   active local challenge and generated-certificate tests.
2. Delete the three generated files. Add only exact or module-scoped ignore entries needed to prevent accidental
   recreation; do not globally ignore all certificates or keys used as deliberate fixtures.
3. Verify `git ls-files net/http-core` contains none of the three paths and inspect module files for private-key
   PEM headers.
4. Run
   `make run-test MODULE=net/http-core TEST='pro.deta.orion.transport.http.ACMECertificateChallengeTest'`.
5. Finish the cleanup leaf as one logical task commit according to the worktree task rules.

### Task 2: Define ACME material descriptors and capability

**Files:**

- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialPurpose.java`
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/AcmeKeyMaterialCapability.java`
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialCapabilities.java`
- Test: `core/key-material/src/test/java/pro/deta/orion/keymaterial/KeyMaterialCapabilitiesTest.java`
- Create or modify: the closeable aggregate material owner under
  `core/key-material/src/main/java/pro/deta/orion/keymaterial/`
- Test: the matching aggregate-owner test under `core/key-material/src/test/java/pro/deta/orion/keymaterial/`

**Steps:**

1. Add failing capability tests for purpose validation, lazy account/domain generation, one durable save,
   existing-key reuse after reopen, chain installation, and mismatched chain rejection.
2. Add `ACME_ACCOUNT` without weakening the existing purpose/algorithm validation.
3. Define the smallest capability needed by the ACME client: obtain the configured account and domain key pairs,
   install a validated X.509 chain for the domain identity, and read the public chain. Do not expose generic
   alias lookup, arbitrary private keys, store mutation, or raw `KeyMaterialService`.
4. Replace the server-identity-only closeable owner with an aggregate owner, or evolve it equivalently, so one
   service creates all typed capabilities and owns save/close. Preserve strict loading of existing server
   identity descriptors.
5. Make lazy ACME initialization concurrency-safe and save only after both configured keys are valid. Never
   overwrite an existing wrong-purpose, wrong-version, wrong-algorithm, or wrong-scope entry.
6. Run
   `make run-test MODULE=core/key-material TEST='pro.deta.orion.keymaterial.KeyMaterialCapabilitiesTest'` and the
   aggregate-owner test.

### Task 3: Replace ACME path configuration with material references

**Files:**

- Modify: `core/schema/src/main/java/pro/deta/orion/schema/config/AcmeConfig.java`
- Modify or create: ACME material-reference configuration under
  `core/schema/src/main/java/pro/deta/orion/schema/config/`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/config/KeyMaterialConfig.java`
- Modify: `core/bootstrap/src/main/resources/config.yml`
- Modify: `core/bootstrap/src/main/resources/config.toml`
- Modify: `connectors/configuration-location/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java`

**Steps:**

1. Change configuration-shape tests to expect account and domain aliases/versions under key-material bootstrap
   configuration and no ACME key or certificate paths.
2. Keep directory URL, account email, allowed domains, organization, terms agreement, and timeouts in operational
   ACME configuration.
3. Add stable defaults for the account and domain material references. Keep their algorithms explicit or fixed
   by validated purpose rather than inferred from existing entries.
4. Remove `accountKeyPath`, `domainKeyPath`, and `certificatePath` from schema and sample configuration. Remove
   the request-level `persist` option when the HTTP model is migrated.
5. Run
   `make run-test MODULE=connectors/configuration-location TEST='pro.deta.orion.config.OrionConfigurationBootstrapShapeTest'`.

### Task 4: Bootstrap one aggregate material owner

**Files:**

- Modify or replace: `core/bootstrap/src/main/java/pro/deta/orion/ServerIdentityMaterialFactory.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/App.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionComponent.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/ServerIdentityMaterialFactoryTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`

**Steps:**

1. Add failing bootstrap tests proving one owner supplies both capabilities, closes once after runtime shutdown,
   does not generate ACME keys while disabled, and generates/reloads them when enabled or requested.
2. Evolve the factory to build server signing, ACME account, and TLS domain descriptors from one configuration
   snapshot and one resolved store.
3. Bind only `ServerIdentityCapability` and `AcmeKeyMaterialCapability` into Dagger. Provide explicit unavailable
   test capabilities where component tests do not exercise ACME; never bind the aggregate owner or raw service.
4. Make `App` own and close the aggregate after application shutdown. Preserve the current bootstrap failure
   behavior and server-identity contract.
5. Run the focused bootstrap tests through `make run-test MODULE=core/bootstrap TEST='<test locator>'`.

### Task 5: Migrate ACME issuance and its HTTP result

**Files:**

- Modify: `net/http-core/pom.xml`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/AcmeCertificateService.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/AcmeCertificateIssuer.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/Acme4jClient.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/AcmeCertificateIssueRequest.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/IssuedAcmeCertificate.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionAdminAcmeCertificateRoute.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/AcmeCertificateIssuerTest.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/AcmeCertificateServiceTest.java`
- Add or modify: `net/http-core` route tests for the certificate endpoint

**Steps:**

1. Add failing tests showing issuance reuses capability-owned keys, installs the returned chain, survives
   capability/store reopen, and creates no legacy files.
2. Change the issuer/client boundary to retain a typed X.509 certificate chain for persistence. PEM encoding is
   an output concern, not the stored domain model.
3. Remove all path resolution, `Files` access, key generation, private-key serialization, and `persist` handling
   from `AcmeCertificateService`.
4. Reduce `IssuedAcmeCertificate` to domains plus certificate chain or certificate-chain PEM. Delete
   `privateKeyPem()` and `nginxPem()`.
5. Make admin GET and POST return certificate chain and metadata only with `Cache-Control: no-store`. Rename
   nginx-specific filenames and remove the implicit combined-PEM download. Do not add a replacement private-key
   response in this task.
6. Add a direct `core/key-material` dependency to `http-core` only for the narrow capability contract.
7. Run the ACME issuer, service, route, and challenge tests through
   `make run-test MODULE=net/http-core TEST='<comma-separated test locator>'`.

### Task 6: Verify boundaries and complete the migration leaf

**Files:**

- Modify: relevant README/configuration documentation if it still advertises ACME path files or nginx PEM
  downloads
- Modify: integration support that constructs `OrionComponent`
- Test: affected startup and HTTP integration tests

**Steps:**

1. Search production code and sample configuration for the removed path getters, legacy ACME filenames,
   `privateKeyPem`, `nginxPem`, and direct ACME `Files` access. Expected result: no active runtime owner remains.
2. Verify generated configuration and startup support bind the unavailable test capability only in tests that do
   not invoke ACME.
3. Run `git diff --check` and `mvn verify -Pdev -T 4` in the dedicated task worktree.
4. Request independent review against `docs/reviews/RULES.md`, with particular attention to private-key exposure,
   aggregate-store ownership, wrong-purpose entries, and partial persistence.
5. Squash the migration work to one task commit, remove the completed leaf and parent link, cherry-pick to
   `main`, run `make test`, and remove the task worktree and branch.
