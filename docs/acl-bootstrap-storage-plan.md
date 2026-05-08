# ACL Bootstrap Storage Plan

## Goal

ACL bootstrap should load ACL configuration without going through Orion network transports.

The server is a Git server, but it still needs a small deployment-level bootstrap configuration before it can serve Git:

- where repository storage lives;
- where ACL storage lives;
- which backend credential sources are needed to open those storages.
- how repositories are created and which transports should start.

ACL must not be required in order to locate or read ACL. After ACL is loaded, normal runtime authorization applies to
external users and transports.

This plan removes ACL loading from the current self-hosted bootstrap loop based on `VolatileUserAdded`, the temporary
`orion_acl` user and event ordering between ACL and Git storage startup. The legacy volatile-user event can remain for
other storage-area startup paths, but ACL bootstrap must not depend on it.

## 1. Keep Deployment Configuration Local

Deployment configuration is local or environment-provided and trusted by deployment.

It has three top-level sections:

- `bootstrap`: startup settings and ACL location;
- `storage`: physical repository storage and repository creation policy;
- `transport`: network transports, started only after storage and ACL are ready.

ACL storage contains only access-control state: users, roles, grants and credentials. Runtime server configuration is not
loaded from the ACL repository and is not stored in Git by this plan.

Target local configuration shape:

```yaml
bootstrap:
  baseDir: /var/lib/orion
  workDir: work
  threadPoolSize: 10
  accessControl:
    location: local:orion
    branch: master
    paths:
      - acl/orion.xml
    createDefaultIfMissing: true

storage:
  location: s3://orion-git/repositories/
  createOnPush: true
  auth:
    region: eu-central-1
    accessKeyId: env:ORION_REPOSITORY_S3_ACCESS_KEY_ID
    secretAccessKey: env:ORION_REPOSITORY_S3_SECRET_ACCESS_KEY

transport:
  defaultAddress: localhost
  git:
    enabled: false
    port: 9418
  ssh:
    enabled: true
    port: 8022
  http:
    enabled: true
    port: 8000
  https:
    enabled: false
    port: 8443
```

## 2. Bootstrap-Supported ACL Locations

For the first version, support only these ACL storage modes:

1. Local filesystem ACL.
2. Remote Git ACL.
3. Git-over-storage ACL.

Other storages such as JDBC and direct S3 object ACL can be added later behind the same ACL storage abstraction.

## 3. Use URI Locators Instead Of A Separate Type Field

ACL storage source should be derived from URI scheme, not from a separate `type` field.

Examples:

```yaml
bootstrap:
  accessControl:
    location: file:/var/lib/orion/acl/
    paths:
      - acl.xml
```

```yaml
bootstrap:
  accessControl:
    location: git+ssh://git@example.com/orion-acl.git
    branch: master
    auth:
      privateKey: file:/etc/orion/keys/acl_ed25519
      passphrase: env:ORION_ACL_KEY_PASSPHRASE
    paths:
      - acl.xml
```

```yaml
bootstrap:
  accessControl:
    location: local:orion
    branch: master
    paths:
      - acl.xml
```

`local:orion` means "open repository `orion` through the already configured repository storage backend". The
repository storage backend itself is configured in bootstrap configuration.

## 4. Repository Storage Bootstrap

Repository storage must be configured and created before ACL can load a `local:` ACL.

Examples:

```yaml
storage:
  location: file:/var/lib/orion/repositories/
  createOnPush: true
```

```yaml
storage:
  location: s3://orion-git/repositories/
  createOnPush: true
  auth:
    region: eu-central-1
    accessKeyId: env:ORION_REPOSITORY_S3_ACCESS_KEY_ID
    secretAccessKey: env:ORION_REPOSITORY_S3_SECRET_ACCESS_KEY
```

Backend authentication belongs to the repository storage backend:

- local filesystem usually needs no backend credential;
- S3 may need credentials from environment variables, files or the default provider chain;
- other backends may have their own credential requirements.

There is no global credential registry and no `credentialRef`. Credential material is configured next to the backend that
uses it. Values should be references such as `env:NAME` or `file:/path/to/secret` whenever possible, not raw secrets in
the configuration file.

Git-over-storage ACL does not require Orion user authorization. It is an internal server read from already configured
repository storage.

## 5. Keep AccessControlStorage As The Main ACL Abstraction

The central ACL API should stay storage-agnostic.

```java
public interface AccessControlStorage {
    Result<AccessControlSnapshot> load();

    Result<Void> save(AccessControlSnapshot snapshot, AccessControlSaveRequest request);
}
```

The exact DTO names can change, but the important part is that `OrionAccessControlServiceImpl` does not know whether ACL
comes from a file, remote Git, Git-over-storage, JDBC or S3.

## 6. Support Multiple ACL Files

Even if the first configuration uses one file, the storage abstraction should support multiple files.

```java
public record AccessControlSnapshot(
        Map<String, byte[]> files,
        Optional<String> version
) {}
```

`version` may be a Git commit id, S3 object version, JDBC revision or any backend-specific opaque value useful for
diagnostics and optimistic updates.

## 7. Implement Initial Storage Backends

Initial implementations:

- `LocalFileAccessControlStorage`
  - supports `file:/...`;
  - reads and writes configured ACL files from a local directory.

- `RemoteGitAccessControlStorage`
  - supports `git+ssh://...` and possibly later `git+https://...`;
  - uses backend credentials declared in the ACL storage config;
  - does not go through Orion's own transports.

- `GitOverRepositoryStorageAccessControlStorage`
  - supports `local:repositoryName`;
  - opens a Git repository through the bootstrap-configured repository storage backend;
  - local backend needs no authorization;
  - S3 backend may need backend credentials declared in the repository storage config;
  - does not use Orion user ACL to read ACL.

## 8. Introduce Repository Storage API

Git-over-storage needs an internal repository storage API that can open repository contents without transport.

This API should represent physical repository storage, not user access.

Possible shape:

```java
public interface RepositoryStorage {
    boolean supports(RepositoryStorageLocator locator);

    Result<StoredRepository> open(String repositoryName);
}
```

For Git-over-storage, `StoredRepository` must allow reading and writing configured files on a branch. The implementation
can use JGit now and later be replaced by S3/native Git storage without changing ACL bootstrap.

## 9. Remove The Volatile Bootstrap Path From ACL Loading

After ACL is loaded through bootstrap storage:

- remove `volatileAccessControl` from `OrionAccessControlServiceImpl`;
- stop subscribing to `VolatileUserAdded` from `OrionAccessControlServiceImpl`;
- keep `VolatileUserAdded`, `assignUserGrants(...)` and legacy `GitBackedInternalStorage` event publishing for existing
  non-bootstrap storage-area behavior;
- update README to describe the new startup model.

## 10. Simplify Lifecycle

Target startup order:

1. Read local deployment configuration.
2. Configure the `storage` backend and repository creation policy.
3. Configure the `bootstrap.accessControl` backend.
4. Load ACL through `AccessControlStorage`.
5. Start `transport` using local deployment configuration.
6. Start serving external users through normal ACL checks.

Important result: ACL startup no longer depends on event subscription order or on Orion transport authentication.

## 11. Add Tests

Minimum tests:

- ACL is loaded from local filesystem without transports;
- ACL is loaded from remote Git without Orion self-transport;
- ACL is loaded from Git-over-local-storage without user authorization;
- Git-over-storage can use backend credentials when the backend requires them;
- default ACL is created and persisted when ACL files are missing;
- multiple ACL files are read in one request;
- ACL reload works after ACL file changes;
- server starts with ACL repository while transports are disabled;
- transport E2E stays separate and no longer participates in ACL bootstrap.
