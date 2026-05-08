# ACL Bootstrap Storage Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Finish the ACL bootstrap storage migration so ACL is loaded from deployment-configured storage without using Orion network transports or volatile bootstrap users.

**Architecture:** Make the new `bootstrap`, `storage`, and `transport` configuration shape primary, while keeping narrow legacy fallbacks until the migration is complete. Resolve ACL storage by URI scheme, route `local:` through an internal repository storage API, and start transports only after repository storage and ACL are ready. Keep legacy `GitBackedInternalStorage` and `VolatileUserAdded` for non-bootstrap storage-area paths only.

**Tech Stack:** Java 21, Maven, Dagger, Jackson YAML, TOML, JGit, JUnit 5, AssertJ.

---

## Current State

Already implemented:

- `AccessControlStorage`, `AccessControlSnapshot`, and `AccessControlSaveRequest` exist.
- `OrionAccessControlServiceImpl` loads ACL through `AccessControlStorage`.
- Local file ACL and local Git ACL storage exist.
- `local:` ACL can be wired to `GitRepositoryProviderImpl.repositoryPath(...)` without legacy `GitAccessControlStorage`.
- `OrionAccessControlServiceImpl` no longer subscribes to `VolatileUserAdded`.
- README describes the target bootstrap flow at a high level.

Main gaps:

- Runtime configuration still uses old `accessControl.type/url`, `git.storagePath`, and `transports`.
- URI scheme does not fully drive ACL storage selection because `ACLStorageType` is still active.
- There is no `RepositoryStorage` API.
- Remote Git ACL bootstrap storage is not implemented as an independent backend.
- Multiple ACL files are supported by the snapshot DTO and merge code, but existing storages only load one configured path.
- `createDefaultIfMissing` is not modeled; any load failure currently creates default ACL.
- `STARTING` lifecycle order still allows transports to start before ACL.

## Commit Strategy

Make one commit per task or per pair of tightly coupled tasks. Use focused Maven tests before each commit and run `mvn test -Pdev` after the final commit in this sequence.

Do not squash during implementation. If a later fix belongs to the same logical change, make a follow-up commit with the same message so it can be squashed later.

## Task 1: Add New Deployment Configuration Shape

**Files:**

- Modify: `core/configuration/src/main/java/pro/deta/orion/config/schema/OrionConfiguration.java`
- Modify: `core/common/src/main/java/pro/deta/orion/util/ConfigurationContext.java`
- Modify: `core/bootstrap/src/main/resources/config.yml`
- Create: `core/configuration/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java`

**Step 1: Write failing configuration parsing tests**

Add tests that parse this YAML and assert the effective values:

```yaml
bootstrap:
  baseDir: /tmp/orion
  workDir: work
  threadPoolSize: 7
  accessControl:
    location: local:orion
    branch: master
    paths:
      - acl/orion.xml
    createDefaultIfMissing: true
storage:
  location: file:/tmp/orion/repositories/
  createOnPush: true
transport:
  defaultAddress: localhost
  git:
    enabled: false
    port: 9418
  ssh:
    enabled: false
    port: 8022
  http:
    enabled: false
    port: 8000
  https:
    enabled: false
    port: 8443
```

Test assertions:

```java
assertThat(configuration.getBootstrap().getBaseDir()).isEqualTo("/tmp/orion");
assertThat(configuration.getBootstrap().getAccessControl().getLocation()).isEqualTo("local:orion");
assertThat(configuration.getBootstrap().getAccessControl().getPaths()).containsExactly("acl/orion.xml");
assertThat(configuration.getStorage().getLocation()).isEqualTo("file:/tmp/orion/repositories/");
assertThat(configuration.getTransport().getHttp().isEnabled()).isFalse();
```

Also add a legacy compatibility test for the current `config.yml` shape. The expected result is that old config still maps to effective bootstrap/storage/transport values.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/configuration -Dtest=OrionConfigurationBootstrapShapeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `bootstrap`, `storage`, and `transport` do not exist.

**Step 3: Implement new config classes**

In `OrionConfiguration`, add:

```java
private BootstrapConfig bootstrap = new BootstrapConfig();
private StorageConfig storage = new StorageConfig();
private AppTransport transport = new AppTransport();
```

Add nested classes:

```java
@Data
public static class BootstrapConfig {
    private String baseDir = "orion";
    private String workDir = "work";
    private int threadPoolSize = 10;
    private BootstrapAccessControlConfig accessControl = new BootstrapAccessControlConfig();
}

@Data
public static class BootstrapAccessControlConfig {
    private String location = "local:orion";
    private String branch = "master";
    private java.util.List<String> paths = new java.util.ArrayList<>(java.util.List.of("orion.xml"));
    private boolean createDefaultIfMissing = true;
    private Map<String, String> auth = new LinkedHashMap<>();
}

@Data
public static class StorageConfig {
    private String location = "file:orion/repos";
    private boolean createOnPush = true;
    private Map<String, String> auth = new LinkedHashMap<>();
}
```

Keep the legacy fields for now:

```java
@Deprecated
private InternalGitServer git = new InternalGitServer();
@Deprecated
private AccessControlConfig accessControl = new AccessControlConfig();
@Deprecated
private AppTransport transports;
```

Add effective getters instead of immediately rewriting all call sites:

```java
public BootstrapConfig effectiveBootstrap() { ... }
public BootstrapAccessControlConfig effectiveAccessControlBootstrap() { ... }
public StorageConfig effectiveStorage() { ... }
public AppTransport effectiveTransport() { ... }
```

Legacy mapping rules:

- old `baseDir`, `workDir`, `threadPoolSize` populate `bootstrap`;
- old `accessControl.url`, `branch`, `settingsFileName` populate `bootstrap.accessControl.location`, `branch`, first `paths` entry;
- old `git.storagePath` becomes `storage.location` via `ConfigurationContext.resolve(...)`;
- old `transports` becomes `transport`.

**Step 4: Update configuration consumers**

Update `ConfigurationContext` to read `effectiveBootstrap()` and `effectiveStorage()`:

```java
public Path getWorkDir() {
    return resolve(configuration.effectiveBootstrap().getWorkDir());
}

public Path getGitStoragePath() {
    return resolveStorageLocation(configuration.effectiveStorage().getLocation());
}
```

Support `file:` URIs and legacy relative paths.

**Step 5: Update bundled config**

Change `core/bootstrap/src/main/resources/config.yml` to the new target shape with transports disabled/enabled exactly as the current defaults require for local startup.

**Step 6: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/configuration,core/bootstrap -am -Dtest=OrionConfigurationBootstrapShapeTest,ConfigurationRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/configuration/src/main/java/pro/deta/orion/config/schema/OrionConfiguration.java core/common/src/main/java/pro/deta/orion/util/ConfigurationContext.java core/bootstrap/src/main/resources/config.yml core/configuration/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java
git commit -m "feat: add bootstrap storage configuration shape"
```

## Task 2: Introduce Repository Storage API

**Files:**

- Create: `core/git-storage/src/main/java/pro/deta/orion/git/storage/RepositoryStorage.java`
- Create: `core/git-storage/src/main/java/pro/deta/orion/git/storage/RepositoryStorageLocator.java`
- Create: `core/git-storage/src/main/java/pro/deta/orion/git/storage/StoredRepository.java`
- Create: `core/git-storage/src/main/java/pro/deta/orion/git/storage/LocalRepositoryStorage.java`
- Create: `core/git-storage/src/test/java/pro/deta/orion/git/storage/LocalRepositoryStorageTest.java`

**Step 1: Write failing tests**

Add tests for:

- `file:/tmp/repos` supports local repository storage;
- repository names are rejected if absolute or escaping with `..`;
- opening a missing repository with `createIfMissing=false` returns `NOT_FOUND`;
- opening with `createIfMissing=true` creates a bare Git repository;
- `StoredRepository` can read and write files on a branch.

Example test shape:

```java
@Test
void opensRepositoryAndWritesFilesOnBranch() {
    LocalRepositoryStorage storage = new LocalRepositoryStorage(locator(tempDir.toUri().toString()), true);

    StoredRepository repository = storage.open("orion").valueOrFailure("repository should open");
    repository.saveFiles("master", Map.of("acl.xml", aclBytes), "seed acl", UserEmail.EMPTY);

    RepositoryFileSnapshot snapshot = repository.loadFiles("master", List.of("acl.xml"))
            .valueOrFailure("file should load");
    assertThat(snapshot.files()).containsOnlyKeys("acl.xml");
}
```

If `RepositoryFileSnapshot` is needed, create it beside `GitFileSnapshot` or reuse `GitFileSnapshot` if the name is acceptable.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/git-storage -Dtest=LocalRepositoryStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because classes do not exist.

**Step 3: Implement API**

Use these minimal interfaces:

```java
public interface RepositoryStorage {
    boolean supports(RepositoryStorageLocator locator);

    Result<StoredRepository> open(String repositoryName);
}
```

```java
public record RepositoryStorageLocator(String scheme, String location, Map<String, String> auth) {
    public static RepositoryStorageLocator parse(String location, Map<String, String> auth) { ... }
}
```

```java
public interface StoredRepository {
    Result<GitFileSnapshot> loadFiles(String branch, List<String> paths);

    void saveFiles(String branch, Map<String, byte[]> files, String message, UserEmail author);
}
```

**Step 4: Implement local storage**

`LocalRepositoryStorage` should:

- support `file:` and legacy relative locations only;
- resolve repository names safely under the storage root;
- use `LocalGitFileStorage` internally;
- honor `createOnPush` for create behavior.

Extend `LocalGitFileStorage` with:

```java
public Result<GitFileSnapshot> load(String branch, List<String> paths)
```

Keep the existing single-path overload and delegate to the list overload.

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/git-storage -Dtest=LocalRepositoryStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/git-storage/src/main/java/pro/deta/orion/git/storage core/git-storage/src/test/java/pro/deta/orion/git/storage/LocalRepositoryStorageTest.java
git commit -m "feat: introduce repository storage api"
```

## Task 3: Route Git Repository Provider Through Storage Configuration

**Files:**

- Modify: `core/git-engine/src/main/java/pro/deta/orion/git/GitRepositoryProviderImpl.java`
- Modify: `core/git-engine/src/test/java/pro/deta/orion/git/GitRepositoryProviderImplTest.java`
- Modify: `core/common/src/main/java/pro/deta/orion/GitRepositoryProvider.java` if a storage-neutral method is needed.

**Step 1: Write failing tests**

Extend `GitRepositoryProviderImplTest` to assert:

- `storage.location: file:<temp>/repos` is used as repository root;
- `createOnPush=false` makes `findOrCreate(...)` fail for a missing repository if that is the desired policy;
- invalid repository names cannot escape the storage root.

**Step 2: Run test and verify failure**

Run:

```bash
mvn test -Pdev -pl core/git-engine -am -Dtest=GitRepositoryProviderImplTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL until provider reads effective storage configuration.

**Step 3: Implement minimal provider update**

Keep `GitRepositoryProviderImpl` local-file backed for this task, but make it consume `ConfigurationContext.getGitStoragePath()` after Task 1's storage parsing.

If `createOnPush=false` is implemented here, add a constructor parameter or effective config access. If it creates too much churn, document it as enforced in Task 4 through `LocalRepositoryStorage`.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/git-engine -am -Dtest=GitRepositoryProviderImplTest,BasicGitInteractionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/git-engine/src/main/java/pro/deta/orion/git/GitRepositoryProviderImpl.java core/git-engine/src/test/java/pro/deta/orion/git/GitRepositoryProviderImplTest.java core/common/src/main/java/pro/deta/orion/GitRepositoryProvider.java
git commit -m "feat: use deployment storage config for repositories"
```

## Task 4: Resolve ACL Storage By URI Scheme

**Files:**

- Create: `core/acl/src/main/java/pro/deta/orion/acl/storage/AccessControlStorageResolver.java`
- Create: `core/acl/src/main/java/pro/deta/orion/acl/storage/BootstrapAccessControlSettings.java` if an ACL-specific adapter is cleaner than passing config objects directly.
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeModule.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`

**Step 1: Write failing resolver tests**

Add tests to `OrionRuntimeModuleTest` or a new resolver test:

```java
assertInstanceOf(LocalFileAccessControlStorage.class, resolve("file:/tmp/acl"));
assertInstanceOf(GitOverRepositoryStorageAccessControlStorage.class, resolve("local:orion"));
assertInstanceOf(RemoteGitAccessControlStorage.class, resolve("git+ssh://git@example.test/orion-acl.git"));
```

Also assert that the resolver does not inspect `ACLStorageType`.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/bootstrap -am -Dtest=OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL until resolver exists and Dagger wiring changes.

**Step 3: Implement resolver**

Rules:

- `file:` -> local filesystem ACL storage;
- `local:` -> Git-over-repository-storage ACL storage;
- `git+ssh:` -> remote Git ACL storage;
- `git+https:` -> remote Git ACL storage if HTTPS auth is implemented in Task 8;
- legacy no-scheme path -> local filesystem ACL only for compatibility;
- unsupported scheme -> fail fast with a clear exception.

**Step 4: Update Dagger wiring**

Replace the `AccessControlStorage` provider that switches on `ACLStorageType` with one that calls the resolver using:

- `orionConfiguration.effectiveAccessControlBootstrap()`;
- configured `RepositoryStorage`;
- providers for storage implementations.

Keep legacy `GitAccessControlStorage` available as a stage listener only for old config fallback until Task 10 removes the bootstrap use.

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/bootstrap,core/acl -am -Dtest=OrionRuntimeModuleTest,AccessControlStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/acl/src/main/java/pro/deta/orion/acl/storage/AccessControlStorageResolver.java core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeModule.java core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java
git commit -m "feat: resolve acl storage by locator scheme"
```

## Task 5: Complete Local File ACL Storage

**Files:**

- Create: `core/acl/src/main/java/pro/deta/orion/acl/storage/LocalFileAccessControlStorage.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java`

**Step 1: Write failing tests**

Add tests:

- local file ACL loads multiple configured files;
- missing one configured file returns `NOT_FOUND`;
- save rejects escaping paths such as `../acl.xml`;
- primary path is the first configured path.

Example:

```java
AccessControlStorage storage = localFileStorage(aclDirectory, List.of("roles.xml", "users.xml"));
AccessControlSnapshot snapshot = storage.load().valueOrFailure("ACL should load");
assertThat(snapshot.files()).containsOnlyKeys("roles.xml", "users.xml");
```

**Step 2: Run test and verify failure**

Run:

```bash
mvn test -Pdev -pl core/acl -am -Dtest=AccessControlStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL for multi-file loading.

**Step 3: Implement storage**

Create `LocalFileAccessControlStorage` using the new bootstrap ACL settings. Keep `LocalAccessControlStorage` as a deprecated compatibility wrapper that delegates to `LocalFileAccessControlStorage`.

Implementation rules:

- load all configured paths;
- normalize and validate each path under the ACL directory;
- return snapshot with all files in configured order;
- save all files from the snapshot.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/acl -am -Dtest=AccessControlStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/acl/src/main/java/pro/deta/orion/acl/storage/LocalFileAccessControlStorage.java core/acl/src/main/java/pro/deta/orion/acl/storage/LocalAccessControlStorage.java core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java
git commit -m "feat: support multi-file local acl storage"
```

## Task 6: Implement Git-Over-Repository ACL Storage

**Files:**

- Create: `core/acl/src/main/java/pro/deta/orion/acl/storage/GitOverRepositoryStorageAccessControlStorage.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/storage/LocalGitAccessControlStorage.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`

**Step 1: Write failing tests**

Add tests:

- `local:orion` opens repository through `RepositoryStorage`, not through `GitRepositoryProviderImpl.repositoryPath(...)`;
- ACL is loaded without Orion user authorization;
- multiple ACL paths are loaded from one repository snapshot;
- save creates initial repository content when allowed.

Use a fake `RepositoryStorage` in the test to assert it receives `orion` and no user identity.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/acl,core/bootstrap -am -Dtest=AccessControlStorageTest,OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `local:` still uses direct repository path wiring.

**Step 3: Implement storage**

`GitOverRepositoryStorageAccessControlStorage` should:

- parse the repository name from `local:` using the existing safe logic;
- call `repositoryStorage.open(repositoryName)`;
- call `StoredRepository.loadFiles(branch, paths)`;
- call `StoredRepository.saveFiles(branch, snapshot.files(), request.message(), request.author())`;
- expose `primaryPath()` as the first configured path.

**Step 4: Keep LocalGitAccessControlStorage compatibility only**

Use `LocalGitAccessControlStorage` for independent file-backed Git repositories if still needed. Do not use it for `local:`.

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/acl,core/bootstrap -am -Dtest=AccessControlStorageTest,OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/acl/src/main/java/pro/deta/orion/acl/storage/GitOverRepositoryStorageAccessControlStorage.java core/acl/src/main/java/pro/deta/orion/acl/storage/LocalGitAccessControlStorage.java core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java
git commit -m "feat: load local acl through repository storage"
```

## Task 7: Honor createDefaultIfMissing

**Files:**

- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java` if the setting is wired there.

**Step 1: Write failing tests**

Add tests:

- missing ACL with `createDefaultIfMissing=true` creates and persists default ACL;
- missing ACL with `createDefaultIfMissing=false` fails startup and does not save;
- parse error does not create default ACL even when `createDefaultIfMissing=true`.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/acl -am -Dtest=AccessControlStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because current service creates default ACL for any load failure path.

**Step 3: Implement behavior**

Inject or pass bootstrap ACL settings into `OrionAccessControlServiceImpl`.

Rules:

- if `load()` returns `NOT_FOUND` and `createDefaultIfMissing=true`, create default ACL;
- if `load()` returns `NOT_FOUND` and `createDefaultIfMissing=false`, fail startup;
- if `load()` returns `GENERAL`, fail startup and preserve the original error;
- never print root password unless default ACL is actually created.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/acl,core/bootstrap -am -Dtest=AccessControlStorageTest,OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java
git commit -m "feat: honor acl default creation policy"
```

## Task 8: Implement Remote Git ACL Storage

**Files:**

- Create: `core/acl/src/main/java/pro/deta/orion/acl/storage/RemoteGitAccessControlStorage.java`
- Create: `core/acl/src/test/java/pro/deta/orion/acl/storage/RemoteGitAccessControlStorageTest.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/storage/AccessControlStorageResolver.java`
- Reuse or modify: `core/git-storage/src/main/java/pro/deta/orion/git/storage/jgit/JGitAuth.java`
- Reuse or modify: `core/git-storage/src/main/java/pro/deta/orion/git/storage/auth/Auth.java`

**Step 1: Write failing tests**

Start with transport-independent tests using a local bare Git repository via `git+file:` if needed for deterministic unit coverage. Add parser tests for `git+ssh:` auth config.

Test cases:

- remote Git storage clones/fetches without Orion transports;
- configured ACL paths load from branch;
- save commits and pushes changes;
- private key auth config is converted to JGit auth for `git+ssh:`;
- missing branch or missing ACL file returns `NOT_FOUND`.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/acl -am -Dtest=RemoteGitAccessControlStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because storage does not exist.

**Step 3: Implement remote storage**

Implementation outline:

- normalize `git+ssh://...` to `ssh://...` before passing to JGit;
- normalize `git+https://...` to `https://...` if HTTPS support is included;
- use a configured work directory under `bootstrap.workDir/acl-remote/<safe-id>`;
- clone if missing, otherwise fetch and checkout target branch;
- read all configured paths from the checked-out tree or directly through JGit;
- commit and push on save.

Auth mapping:

- `auth.privateKey=file:/path/key` -> SSH key auth;
- `auth.passphrase=env:NAME` -> resolve from environment only at runtime;
- `auth.username` and `auth.password=env:NAME` -> HTTPS auth if implemented.

Do not support raw secret values unless there is an explicit reason. Prefer `env:` and `file:`.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/acl -am -Dtest=RemoteGitAccessControlStorageTest,AccessControlStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/acl/src/main/java/pro/deta/orion/acl/storage/RemoteGitAccessControlStorage.java core/acl/src/test/java/pro/deta/orion/acl/storage/RemoteGitAccessControlStorageTest.java core/acl/src/main/java/pro/deta/orion/acl/storage/AccessControlStorageResolver.java core/git-storage/src/main/java/pro/deta/orion/git/storage/jgit/JGitAuth.java core/git-storage/src/main/java/pro/deta/orion/git/storage/auth/Auth.java
git commit -m "feat: add remote git acl bootstrap storage"
```

## Task 9: Enforce Startup Order

**Files:**

- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java` if explicit transport priority is clearer.
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java`
- Create: `core/bootstrap/src/test/java/pro/deta/orion/component/BootstrapStartupOrderTest.java`

**Step 1: Write failing startup order test**

Create a lifecycle test with fake listeners or inspect registered listeners through a test-only helper.

Expected order for `STARTING`:

1. repository storage is configured;
2. ACL loads and waits for completion;
3. transports start.

At minimum assert:

```java
assertThat(aclStartingPriority).isLessThan(httpStartingPriority);
assertThat(aclStartingPriority).isLessThan(gitStartingPriority);
assertThat(aclStartingListenerWaitsForCompletion).isTrue();
```

**Step 2: Run test and verify failure**

Run:

```bash
mvn test -Pdev -pl core/bootstrap -am -Dtest=BootstrapStartupOrderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because current ACL priority is after transports.

**Step 3: Implement order**

Use explicit constants:

```java
public static final int ACL_BOOTSTRAP_START_PRIORITY = -20;
public static final int TRANSPORT_START_PRIORITY = 20;
```

Register ACL load:

```java
registrar.register(ApplicationState.STARTING, this::onStart)
        .priority(ACL_BOOTSTRAP_START_PRIORITY)
        .waitForCompletion();
```

Set HTTP, Git, and SSH transports to `TRANSPORT_START_PRIORITY` or later.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/bootstrap,net/http-core,net/git-transport -am -Dtest=BootstrapStartupOrderTest,JettyHTTPServerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java core/bootstrap/src/test/java/pro/deta/orion/component/BootstrapStartupOrderTest.java
git commit -m "fix: start transports after acl bootstrap"
```

## Task 10: Remove Legacy Bootstrap Coupling From ACL Path

**Files:**

- Modify: `core/acl/src/main/java/pro/deta/orion/acl/storage/GitAccessControlStorage.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeModule.java`
- Modify: `core/git-storage/src/main/java/pro/deta/orion/git/storage/GitBackedInternalStorage.java` only if naming or comments need clarification.
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java`

**Step 1: Write failing tests**

Add tests asserting:

- new bootstrap ACL config never requests `GitAccessControlStorage`;
- `VolatileUserAdded` is not needed for ACL load;
- legacy storage-area registration can still publish `VolatileUserAdded` for old non-bootstrap behavior.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/acl,core/bootstrap,core/git-storage -am -Dtest=AccessControlStorageTest,OrionRuntimeModuleTest,GitBackedInternalStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL if a new `GitBackedInternalStorageTest` does not exist yet or if legacy path still appears in bootstrap resolver.

**Step 3: Implement cleanup**

Rules:

- `GitAccessControlStorage` remains only as a legacy storage-area implementation;
- new `bootstrap.accessControl.location` never resolves to `GitAccessControlStorage`;
- `VolatileUserAdded`, `assignUserGrants(...)`, and `GitBackedInternalStorage.registerArea(...)` remain available for non-bootstrap storage areas;
- remove any README or comments that imply ACL bootstrap uses temporary users.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/acl,core/bootstrap,core/git-storage -am -Dtest=AccessControlStorageTest,OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/acl/src/main/java/pro/deta/orion/acl/storage/GitAccessControlStorage.java core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeModule.java core/git-storage/src/main/java/pro/deta/orion/git/storage/GitBackedInternalStorage.java core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java core/acl/src/test/java/pro/deta/orion/acl/storage/AccessControlStorageTest.java
git commit -m "refactor: isolate legacy git storage areas from acl bootstrap"
```

## Task 11: Add End-to-End Bootstrap Test With Transports Disabled

**Files:**

- Create: `core/bootstrap/src/test/java/pro/deta/orion/component/AclBootstrapLifecycleTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java` only if shared helpers are extracted.

**Step 1: Write failing lifecycle test**

Test scenario:

- configuration uses `bootstrap.accessControl.location: local:orion`;
- all transports are disabled;
- repository storage has an `orion` repository with ACL file;
- full application lifecycle reaches `UP`;
- a user from ACL can authenticate;
- no Git native, SSH, HTTP, or HTTPS socket starts.

**Step 2: Run test and verify failure if lifecycle wiring is incomplete**

Run:

```bash
mvn test -Pdev -pl core/bootstrap -am -Dtest=AclBootstrapLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL until all wiring supports the new config end to end.

**Step 3: Implement minimal lifecycle wiring fixes**

Only fix issues found by the test. Do not add new storage types in this task.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/bootstrap -am -Dtest=AclBootstrapLifecycleTest,OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/bootstrap/src/test/java/pro/deta/orion/component/AclBootstrapLifecycleTest.java core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java
git commit -m "test: cover acl bootstrap with transports disabled"
```

## Task 12: Update Documentation

**Files:**

- Modify: `README.md`
- Modify: `docs/acl-bootstrap-storage-plan.md`
- Modify: `core/bootstrap/src/main/resources/config.yml`
- Modify: `core/bootstrap/src/main/resources/config.toml` if it is still shipped.

**Step 1: Update README**

Document:

- `bootstrap.baseDir`;
- `bootstrap.workDir`;
- `bootstrap.accessControl.location`;
- `bootstrap.accessControl.paths`;
- `bootstrap.accessControl.createDefaultIfMissing`;
- `storage.location`;
- `storage.createOnPush`;
- `transport.*.enabled`.

**Step 2: Update plan document status**

Append a short "Implementation status" section to `docs/acl-bootstrap-storage-plan.md` with:

- implemented backends;
- unsupported backends;
- compatibility notes for legacy config.

**Step 3: Check docs**

Run:

```bash
rg -n "accessControl\\.type|accessControl\\.url|transports|git\\.storagePath|VolatileUserAdded.*ACL" README.md docs core/bootstrap/src/main/resources
```

Expected: only legacy compatibility notes remain.

**Step 4: Commit**

```bash
git add README.md docs/acl-bootstrap-storage-plan.md core/bootstrap/src/main/resources/config.yml core/bootstrap/src/main/resources/config.toml
git commit -m "docs: document acl bootstrap storage configuration"
```

## Task 13: Final Verification

**Files:**

- No planned source changes.

**Step 1: Run focused suites**

Run:

```bash
mvn test -Pdev -pl core/acl,core/bootstrap,core/git-storage,core/git-engine -am -Dtest=AccessControlStorageTest,RemoteGitAccessControlStorageTest,LocalRepositoryStorageTest,OrionRuntimeModuleTest,AclBootstrapLifecycleTest,GitRepositoryProviderImplTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 2: Run full regular Maven tests**

Run:

```bash
mvn test -Pdev
```

Expected: PASS.

If it fails with JGit temporary `.probe-*` cleanup once, rerun once and record it as an existing flaky cleanup issue only if the second run passes.

**Step 3: Inspect working tree**

Run:

```bash
git status --short
```

Expected: clean.

**Step 4: Final report**

Report:

- commits created;
- focused test results;
- full `mvn test -Pdev` result;
- remaining unsupported storage schemes, if any.
