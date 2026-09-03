# Bootstrap Proxy Runtime Integration Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to
> implement this plan task-by-task.

**Goal:** Load `orion.xml` and the encrypted PKCS12 material store through one
proxy-aware native Git provider before constructing the ACL and public
transports.

**Architecture:** Process TOML/YAML describes the configuration document and
material store independently. A pre-Dagger bootstrap context creates the native
repository backend, installs provisional remote bindings, resolves both source
paths, and opens the existing typed `ServerIdentityMaterial`; the resulting
provider, source handles, and identity capability are then bound into the Orion
component. ACL sees only a resolved local repository handle and never knows
whether that repository is proxy-backed.

**Tech Stack:** Java 21, Dagger, Orion native Git storage/client protocols,
typed key-material capabilities, Apache MINA SSHD, JDK HTTP client, JUnit 5,
AssertJ, Maven.

---

### Task 1: Rebase the task on the current runtime contracts

**Files:**
- Rebase: task branch onto the exact plan commit on `main`
- Preserve: `core/schema/src/main/java/pro/deta/orion/schema/config/KeyMaterialConfig.java`
- Preserve: `core/key-material/src/main/java/pro/deta/orion/keymaterial/ServerIdentityMaterial.java`
- Preserve: current native pack-ingestion and Git transport APIs

1. Rebase without a merge commit and resolve every conflict against current
   `main` behavior.
2. Keep typed material capabilities, server identity/JWT material, streaming
   pack APIs, and completed architecture-review removals.
3. Do not restore JDBC or S3 ACL storage removed from `main`.
4. Drop the branch-local obsolete implementation-plan text in favor of this
   committed plan.
5. Run `git diff --check` and inspect the complete diff from the new base.

### Task 2: Model both bootstrap inputs without ACL ownership

**Files:**
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/config/OrionConfiguration.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/config/KeyMaterialConfig.java`
- Add or modify: a shared bootstrap source descriptor under
  `core/schema/src/main/java/pro/deta/orion/schema/config/`
- Modify: `core/bootstrap/src/main/resources/config.toml`
- Modify: `core/bootstrap/src/main/resources/config.yml`
- Modify: `connectors/configuration-location/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/ConfigurationRuntimeTest.java`

1. Write schema tests for independent `bootstrap.configuration` and
   `bootstrap.keyMaterial` inputs. Each Git-capable input retains `location`,
   selected `ref`, repository `path`, and transport `auth`; key material also
   retains its external `password`, creation policy, cluster id, and signing
   descriptors.
2. Replace bootstrap ACL locator naming with a configuration-source descriptor.
   ACL remains a later projection that consumes the resolved `orion.xml`.
3. Keep the current typed key-material fields. Do not replace
   `KeyMaterialConfig` with a metadata-only or validation-only descriptor.
4. Make the shipped defaults colocated in one native repository:

   ```toml
   [bootstrap.configuration]
   location = "local:orion"
   ref = "refs/heads/main"
   path = "orion.xml"
   createDefaultIfMissing = true

   [bootstrap.keyMaterial]
   location = "local:orion"
   ref = "refs/heads/main"
   path = "material.p12"
   password = "env:ORION_KEY_MATERIAL_PASSWORD"
   createIfMissing = false
   ```

5. Retain `clusterId`, `serverSigning`, and future typed material configuration
   below `bootstrap.keyMaterial` in both shipped files.
6. Assert that a partial key-material section defaults to `local:orion`, main,
   and `material.p12`, never `orion.xml`.
7. Run focused configuration tests with the `dev` profile.

### Task 3: Provide canonical transparent Git aliases

**Files:**
- Create or update: `git/git-native-proxy/`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryProvider.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java`
- Test: corresponding proxy, native service, HTTP, SSH, and client transport tests

1. Retain `git+ssh`, `git+http`, `git+https`, and `git+file`; leave `local:`
   direct.
2. Derive a provisional alias from a canonical transport URI plus full ref.
   Normalize scheme and host case, equivalent file paths, default ports, and
   dot segments while preserving repository-significant encoded path data,
   SSH username, and non-default port.
3. Prove equivalent locations share one alias and distinct upstream/ref pairs
   do not. Different file paths inside one upstream ref must share the alias.
4. Resolve credentials only from external `env:` or protected `file:`
   references. Reject credential-bearing URIs and sanitize failures.
5. Refresh a proxy once when acquiring a logical read/write handle. Route all
   mutations and receive-pack publications through upstream compare-and-set;
   do not expose a mutable raw backend handle.
6. Adapt the branch implementation to current streaming pack APIs rather than
   reverting them.
7. Run focused proxy, native Git service, Smart HTTP, and SSH tests.

### Task 4: Open Git-backed material before Dagger runtime activation

**Files:**
- Create: bootstrap context/factory classes under
  `core/bootstrap/src/main/java/pro/deta/orion/`
- Create: a native Git `KeyMaterialContentStore` adapter under
  `core/bootstrap/src/main/java/pro/deta/orion/`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/ServerIdentityMaterialFactory.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/App.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionComponent.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeModule.java`
- Modify: `net/transport/src/main/java/pro/deta/orion/transport/OrionTransportModule.java`
- Test: bootstrap, application, material-factory, and lifecycle tests

1. Write a failing test that seeds `orion.xml` and an encrypted PKCS12 in one
   local native repository, opens bootstrap, and observes one repository alias.
2. Write a remote `git+file` test proving two paths in one upstream/ref fetch
   through one hidden proxy before ACL construction.
3. Build one bootstrap context before constructing `DaggerOrionComponent`. It
   owns the proxy-aware provider, immutable resolved source handles, and opened
   `ServerIdentityMaterial`.
4. Bind those exact instances into the component so ACL, HTTP, and SSH use the
   same provider and capability. Do not build a second backend or refetch through
   a second provider.
5. Implement a `KeyMaterialContentStore` adapter over a resolved native Git
   repository/ref/path. Reads return durable revisions; writes preserve the
   `KeyMaterialContentStore` optimistic-concurrency contract and publish through
   provider-level proxy compare-and-set.
6. Refactor `ServerIdentityMaterialFactory` to accept an already resolved
   content store while preserving password resolution, typed signing
   descriptors, and secure option cleanup.
7. For direct file material, continue using `KeyMaterialResourceResolver` and
   `LocalKeyMaterialContentStore` so owner-only, regular-file, and no-symlink
   checks remain enforced.
8. Any missing source, bad credential, corrupt PKCS12, or wrong password must
   fail before component construction. Error text must not disclose credentials,
   private keys, keystore bytes, or sensitive paths.
9. Close the bootstrap-owned material on normal shutdown and every failed
   construction path.

### Task 5: Make ACL consume only the resolved configuration source

**Files:**
- Modify: `connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/AccessControlStorageResolver.java`
- Modify: `connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Test: ACL storage and bootstrap lifecycle tests

1. Require the resolved configuration handle before constructing ACL storage.
2. Convert a repository-backed source to its logical local alias inside the
   resolver. ACL must not parse `git+...`, fetch upstreams, resolve bootstrap
   credentials, validate material, or access a proxy registry.
3. Retain provider-level read/save operations so proxy policy cannot be
   bypassed.
4. Create the default ACL only when `orion.xml` is genuinely missing and
   `createDefaultIfMissing=true`; material failure must never trigger ACL
   creation.
5. Prove ACL and transports remain unconstructed when either bootstrap input
   fails.

### Task 6: Verify the integrated runtime

1. Run focused tests for schema, bootstrap, key material, proxy transports,
   ACL storage, native Git service, and HTTP routes with `-Pdev -T 4` and `-am`.
2. Run `mvn verify -Pdev -T 4` and classify any environment-only failures.
3. Commit with the task subject required by `AGENTS.md`, then run `make test`.
4. Review the complete branch diff against the rebased `main` commit and confirm
   no current-main functionality or completed task result was reverted.
