# Unified Resource Storage Resolution

## Goal

Unify the logic that determines storage implementations from resource locations.

Orion currently has three separate resolution layers:

1. application configuration resolution from application parameters;
2. Git repository storage resolution from `storage.location` in bootstrap
   configuration;
3. ACL bootstrap storage resolution from `bootstrap.accessControl.location`.

These layers all parse locations and select implementations, but they do it with
different switch statements and different helper APIs. The target design should
use one shared backend-selection concept while still preserving the different
runtime constraints of each use case.

## Core Idea

Do not force every use case into one concrete storage interface. A configuration
file, a versioned ACL store, and a Git repository provider do not have identical
APIs.

Instead, create one shared resolution layer that maps:

```text
location + usage + phase + required capabilities + options
```

to the correct backend implementation or to a clear error.

The result type may differ by use case:

- content store;
- versioned content store;
- Git repository provider;
- read-only bootstrap source.

But the rules for interpreting `file:`, empty paths, `s3:`, `git+...`,
`local:`, `classpath:`, `env:`, and `content:` should live in one registry.

## Content Access vs Storage Access

The shared resolver should distinguish "read this referenced thing" from "open
this as a storage backend".

For read-only inputs such as application configuration, bootstrap files, and
secrets, the resolved implementation can be a content source:

```text
ResourceContent / ContentSource
```

The resolver owns the small client needed to perform the read:

- local file reader for plain paths and `file:`;
- classpath reader for bundled defaults;
- HTTP/HTTPS client for URL content;
- S3-compatible object client for `s3:`;
- Git single-path reader for `git+file:`, `git+ssh:`, `git+http:`, and
  `git+https:`.

For mutable domains such as ACL storage, token storage, key material storage, or
future repository-backed state, the caller should request a storage capability
instead of raw content. The returned implementation must be a read-write store
when the requested capabilities include writing:

```text
READ_CONTENT -> content source
READ_CONTENT + WRITE_CONTENT -> writable content store
READ_CONTENT + WRITE_CONTENT + VERSIONED_CONTENT -> versioned content store
REPOSITORY_STORAGE -> repository provider
```

This keeps configuration and secret loading simple while letting ACL resolve to
a storage abstraction backed by a local directory, S3, Git, or another backend.
The feature layer should not need to know whether the resolver used HTTP, S3, or
Git clients internally to access the requested path.

## Request Model

Introduce a request object:

```java
record ResourceStorageRequest(
        String location,
        ResourceUsage usage,
        BootstrapPhase phase,
        Set<ResourceCapability> requiredCapabilities,
        Map<String, String> options) {
}
```

Example usages:

```text
usage = CONFIGURATION_FILE
phase = PRE_CONFIGURATION
required = READ_CONTENT

usage = GIT_REPOSITORY_STORAGE
phase = RUNTIME_BOOTSTRAP
required = REPOSITORY_STORAGE

usage = ACL_STORAGE
phase = RUNTIME_BOOTSTRAP
required = READ_CONTENT + WRITE_CONTENT
```

Example capabilities:

```text
READ_CONTENT
WRITE_CONTENT
VERSIONED_CONTENT
REPOSITORY_STORAGE
READ_ONLY
PRE_CONFIGURATION_SAFE
RUNTIME_ONLY
```

## Backend Registry

Add a `ResourceBackendRegistry` with pluggable backends:

```text
FileResourceBackend
ClasspathResourceBackend
S3ResourceBackend
RemoteGitResourceBackend
LocalOrionRepositoryBackend
EnvResourceBackend
ContentResourceBackend
```

Each backend declares:

- supported schemes;
- supported capabilities;
- allowed bootstrap phases;
- whether the backend is read-only or writable;
- how it maps query/auth/options to implementation-specific settings.

The registry should use `ResourceLocation` and `ResourceScheme` as the common
parser. Query parameter parsing, secret reference handling, S3 bucket/key
parsing, and remote Git URI normalization should move out of feature-specific
resolvers into shared helpers.

## Bootstrap Phase Rules

Configuration resolution happens before the application configuration exists.
Therefore it can only use backends marked `PRE_CONFIGURATION_SAFE`.

Allowed for application configuration:

- empty scheme plain path;
- `file:`;
- `classpath:`;
- `env:`;
- `content:`;
- `s3:`;
- `git+file:`;
- `git+ssh:`;
- `git+http:`;
- `git+https:`.

Rejected for application configuration:

- `local:`.

The important distinction is that `git+file:/some/repo.git?path=orion.yml` is
an external Git repository and can be used before Orion starts. `local:...` is
an internal Orion repository location and cannot be resolved before the runtime
storage is initialized.

The error should be explicit:

```text
local: locations cannot be used for application configuration because Orion
storage is not initialized yet
```

## Result Model

Use a sealed result hierarchy rather than returning `Object`:

```java
sealed interface ResolvedResourceStorage {
}

record ResolvedContentStore(ContentStore store) implements ResolvedResourceStorage {
}

record ResolvedVersionedContentStore(VersionedContentStore store)
        implements ResolvedResourceStorage {
}

record ResolvedRepositoryProvider(GitRepositoryProvider provider)
        implements ResolvedResourceStorage {
}
```

Concrete names can change during implementation, but the important boundary is
that feature code asks for capabilities and receives a typed backend, not a raw
scheme decision.

## Current Layer Mapping

### Configuration

`LocationConfigurationProvider` should stop owning separate
`ConfigurationLocationReader` implementations. Instead it should request:

```text
CONFIGURATION_FILE + PRE_CONFIGURATION + READ_CONTENT
```

Then it parses the returned bytes as YAML or TOML based on the source name or
declared content metadata.

### Git Repository Storage

`GitRepositoryProviderResolver` should stop switching directly on
`storage.location`. It should request:

```text
GIT_REPOSITORY_STORAGE + RUNTIME_BOOTSTRAP + REPOSITORY_STORAGE
```

Current mappings:

- empty path / `file:` -> `FileGitRepositoryProvider`;
- `s3:` -> `S3GitRepositoryProvider`.

Other schemes should fail with a capability error, not with an unrelated
scheme-specific message.

### ACL Storage

`AccessControlStorageResolver` should stop owning the full switch over local,
remote Git, and S3. It should request:

```text
ACL_STORAGE + RUNTIME_BOOTSTRAP + READ_CONTENT + WRITE_CONTENT
```

Then adapt the resolved backend:

- plain path / `file:` -> non-versioned file content store;
- `s3:` -> non-versioned object content store;
- `git+file:`, `git+ssh:`, `git+http:`, `git+https:` -> versioned remote Git
  content store;
- `local:` -> versioned content store backed by the runtime
  `GitRepositoryProvider`.

The ACL layer may still need ACL-specific behavior around paths, branch names,
default file creation, and snapshot metadata, but it should not own backend
selection.

## Versioned vs Non-Versioned Storage

Versioning should be expressed as a capability rather than a separate resolver.

Examples:

- Configuration file load requires only `READ_CONTENT`.
- Runtime Git repository storage requires `REPOSITORY_STORAGE`.
- ACL storage can use non-versioned content stores, but should prefer or adapt
  versioned content stores when the backend supports `VERSIONED_CONTENT`.
- Future key material storage can request `READ_CONTENT + WRITE_CONTENT` and
  optionally use version checks for S3 ETags or Git commits.

If a caller requires versioning and the location resolves to a non-versioned
backend, the resolver should fail before feature code starts.

## Migration Steps

1. Extract common location helpers:
   - query parsing;
   - first-present query value lookup;
   - secret reference handling;
   - remote Git URI normalization;
   - S3 bucket/key parsing;
   - path normalization and traversal rejection.

2. Add `ResourceUsage`, `BootstrapPhase`, `ResourceCapability`, and
   `ResourceStorageRequest`.

3. Add `ResourceBackendRegistry` and initial backend declarations for existing
   schemes.

4. Add content-store interfaces:
   - read-only content source;
   - writable content store;
   - versioned content store.

5. Move file, classpath, S3, and remote Git single-file read/write code behind
   those stores.

6. Convert `LocationConfigurationProvider` to use the shared resolver in
   `PRE_CONFIGURATION` mode.

7. Convert `GitRepositoryProviderResolver` to use the shared resolver for
   `REPOSITORY_STORAGE`.

8. Convert `AccessControlStorageResolver` to use the shared resolver and only
   handle ACL-specific adaptation.

9. Remove duplicated scheme switches from the old resolver classes.

## Test Matrix

Cover at least these cases:

- configuration + plain path resolves to file content;
- configuration + `file:` resolves to file content;
- configuration + `classpath:` resolves read-only content;
- configuration + `s3:` resolves S3 content;
- configuration + `git+file:` resolves external Git file content;
- configuration + `local:` fails because runtime storage is unavailable;
- repository storage + empty path resolves file repository provider;
- repository storage + `file:` resolves file repository provider;
- repository storage + `s3:` resolves S3 repository provider;
- repository storage + `git+ssh:` fails because it does not provide
  `REPOSITORY_STORAGE`;
- ACL + `local:` resolves versioned internal repository content;
- ACL + `s3:` resolves non-versioned object content;
- ACL + `git+ssh:` resolves versioned remote Git content;
- a caller requiring `VERSIONED_CONTENT` fails against plain `file:` or `s3:`
  unless that backend explicitly provides version checks.

## Expected Outcome

After this change Orion should have one way to determine which backend a
location names. Differences between configuration loading, Git repository
storage, ACL storage, and future key material storage should be represented by
usage, phase, and required capabilities, not by separate hand-written switch
statements.
