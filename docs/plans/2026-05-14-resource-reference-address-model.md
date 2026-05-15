# Resource Reference and Address Model

## Goal

Replace the current `ResourceLocation` concept with clearer terminology and a
model that supports nested resource schemes.

The existing name suggests a simple URI or path, but Orion uses these values to
select backend implementations and, increasingly, to resolve indirection through
environment variables, inline content, object storage, Git, and local files.

The new model should distinguish:

- a raw user/config expression that may contain indirection;
- a resolved concrete address that can be passed to a backend;
- the normalized scheme used for backend selection.

## Current Discussion Status - 2026-05-15

The design has moved away from colon-only nested references such as
`file:env:ORION_CONFIG` as the primary syntax. That form is hard to read once
defaults, paths, document dereferencing, and nested sources are combined. The
sections below still describe the earlier direction and should be treated as
historical context until this plan is rewritten.

The current syntax and implementation contract are now documented in
`core/resource-addressing/README.md` and
`docs/plans/2026-05-15-resource-reference-implementation.md`.

The current preferred direction is shell-style interpolation:

```text
$ORION_ROOT
${ORION_ROOT}
${ORION_ROOT:-orion_root}
$ORION_ROOT/orion.xml
$ORION_ROOT/$PATH_TO_CONFIG
${ORION_ROOT:-orion_root}/orion.xml
${bootstrap.baseDir}/orion.xml
```

Rules currently agreed:

- `$NAME` and `${NAME}` read an immutable scalar value.
- Four Bash-compatible default/required operators are supported for every
  reference type, not only environment variables:
  - `${NAME:-default}` uses `default` when `NAME` is unset or blank.
  - `${NAME-default}` uses `default` only when `NAME` is unset; blank is a valid
    value.
  - `${NAME:?message}` fails with `message` when `NAME` is unset or blank.
  - `${NAME?message}` fails with `message` only when `NAME` is unset; blank is a
    valid value.
- Blank means `String.isBlank()` after the referenced value has been converted
  to a string.
- Assignment-style operators such as `${NAME:=default}` and `${NAME=default}`
  are intentionally not supported because resolution is immutable.
- `$NAME` is only a shorthand for simple names matching
  `[A-Za-z_][A-Za-z0-9_]*`. It has no defaults, no dotted names, and no nested
  syntax. Use `${...}` for every complex reference.
- Names without dots are environment references by default.
- Dotted names such as `${bootstrap.baseDir}` refer to the current resolution
  context or configuration model.
- References may point back into the same configuration model, so resolution
  must detect direct and indirect cycles.
- Interpolation itself is read-only and immutable. Writable capabilities are
  selected later by the call site.

The call site should ask for a capability, not always for a low-level Java type.
Examples:

```java
resolver.resolve(value, ResourceContent.class)
resolver.resolve(value, Path.class)
resolver.resolve(value, ExternalDirectory.class)
resolver.resolve(value, RepositoryStorage.class)
```

This keeps address schemes from encoding operations. For example, `file:` should
mean "local file resource", not always "read file now". If the call site asks for
`Path`, it receives a path. If it asks for `ResourceContent`, the file resource
is read as an immutable content snapshot. If the call site does not yet know the
capability, it should first resolve to a neutral resolved reference/address and
then dispatch by scheme and usage.

The resource-addressing module should provide lightweight built-in resolvers for
the common schemes needed by this model: `file:`, `http:`, `https:`, `s3:`,
`git:`, and remote Git schemes such as `git+ssh:`, `git+http:`, and
`git+https:`. These resolvers should avoid depending on the heavier runtime
storage or transport implementations. They exist to parse addresses, perform
small immutable reads when a call site requests `ResourceContent`, and keep
reference resolution behavior deterministic and testable.

Resolver selection must be extensible. Other modules should be able to
contribute scheme/capability resolvers through an explicit registry or provider
interface without changing the core parser. For example, the Orion runtime
module can register a resolver for `local:` so `local:orion` is interpreted as a
reference to an internal local `GitRepository` in Orion's configured repository
storage, while the core resource-addressing module can still treat `local:` as
unknown unless that resolver is installed.

Inline Kubernetes-style config data should be expressible explicitly. The
current candidate is `content:` for literal content, while address schemes such
as `file:`, `s3:`, and `git+...` still identify resources that can produce
content:

```text
content:${ORION_CONFIG_DATA}
file:${ORION_CONFIG_PATH:-config.yml}
s3://${CONFIG_BUCKET}/orion.yml
```

Document dereferencing is still open. The requirement is to parse YAML, TOML, or
XML content and read a property/path from it. Current examples:

```text
${yaml:${ORION_CONFIG_DATA}/bootstrap/baseDir}
${yaml:$ORION_CONFIG_DATA/bootstrap/baseDir}
${yaml:$ORION_CONFIG_PATH/bootstrap/baseDir}
${yaml:${ORION_CONFIG_PATH:-config.yml}/bootstrap/baseDir}
${yaml:${bootstrap.configPath}/bootstrap/baseDir}
${xml:${ORION_ACL_PATH:-orion.xml}/accessControl/users/root}
```

The earlier `#` separator idea, for example
`${xml:file:${ORION_ACL_PATH:-orion.xml}#/accessControl/users/root}`, is not
settled. The current preference is to rely on nested `${...}` boundaries where
needed instead of adding another separator. One unresolved point is how a schema
reference such as `yaml:` or `xml:` decides whether its source is inline content,
a local path, or another URL/address. The likely direction is:

- parse inline content directly when the source is known content;
- read `file:`, `s3:`, `git+...`, and similar address schemes as
  `ResourceContent`;
- allow `$NAME`, `${NAME}`, `${NAME:-default}`, and `${context.name}` as schema
  sources, but parse them as AST nodes rather than concatenating strings first;
- require a schema source to be either a nested `$...`/`${...}` reference or an
  explicit address scheme. Plain inline text in `yaml:`/`xml:` without a nested
  reference is not part of the current design;
- after resolving the schema source, parse it directly if it resolves to
  `ResourceContent` or obvious inline scalar content;
- if the source resolves to `Path` or a valid address/URI, read it as
  `ResourceContent` and then parse it;
- if a source looks like a path or URI but cannot be read, fail rather than
  silently treating the path string as inline document content.

The schema reference must therefore be parsed as a structured node, for example:

```text
DocumentReference(
  schema = yaml,
  source = $ORION_CONFIG_PATH,
  pointer = /bootstrap/baseDir
)
```

The resolver should not first interpolate everything into one flat string and
then try to rediscover where the document source ended and the document pointer
began.

Before implementation, this section should be turned into a revised syntax
specification and test matrix for the `core/resource-addressing` module.

## Naming

Use these names:

- `ResourceReference`: raw expression from configuration, CLI arguments, or API
  input. It may contain nested schemes such as `file:env:ORION_CONFIG`.
- `ResourceAddress`: resolved concrete address after reference dereferencing.
  This is what backend selection consumes.
- `ResourceScheme`: normalized scheme value. This can keep the current name or
  become `ResourceAddressScheme` if the rename is done broadly.

Examples:

```text
ResourceReference.parse("file:env:ORION_CONFIG")
ResourceAddress.parse("file:/etc/orion/orion.yml")
ResourceScheme.FILE
```

## Nested Scheme Semantics

Nested references allow the outer scheme to declare the expected backend while
the inner expression supplies the backend operand.

Example:

```text
file:env:ORION_CONFIG
```

Resolution:

1. `env:ORION_CONFIG` reads the environment variable value.
2. The result is used as the operand for `file:`.
3. The final address is a file address.

This lets operators keep configuration stable while moving the concrete path or
URI into the environment:

```yaml
security:
  keyStore:
    location: file:env:ORION_KEYSTORE_PATH
```

The config file says "this is a file-backed keystore"; the environment decides
which file.

## Supported Forms

Direct addresses:

- `/etc/orion/orion.yml`
- `file:/etc/orion/orion.yml`
- `s3://bucket/path/orion.yml`
- `git+ssh://git@example.test/orion/config.git?path=orion.yml`
- `local:orion/config`
- `classpath://defaults/orion.yml`

Reference providers:

- `env:NAME`
- `content:...`
- `content:base64,...`

Nested references:

- `file:env:ORION_CONFIG`
- `s3:env:ORION_CONFIG_S3_URI`
- `git+ssh:env:ORION_CONFIG_GIT_URI`
- `content:env:ORION_INLINE_CONFIG`
- `file:content:/tmp/orion.yml`

The exact normalization for nested values should be explicit. For example, if
`ORION_CONFIG=/etc/orion/orion.yml`, then `file:env:ORION_CONFIG` resolves to
`file:/etc/orion/orion.yml`. If `ORION_CONFIG=file:/etc/orion/orion.yml`, the
resolver should not produce `file:file:/...`; it should recognize that the inner
value is already a file address and keep it equivalent to the outer scheme.

## Resolution API

Introduce a resolver boundary:

```java
ResourceReference reference = ResourceReference.parse("file:env:ORION_CONFIG");
ResourceAddress address = resourceReferenceResolver.resolve(reference, context);
```

Context should include:

```text
phase
usage
allowed capabilities
max dereference depth
whether secrets may be revealed
```

Example:

```java
record ResourceResolutionContext(
        BootstrapPhase phase,
        ResourceUsage usage,
        Set<ResourceCapability> requiredCapabilities,
        int maxDepth) {
}
```

The resolved `ResourceAddress` then goes to `ResourceBackendRegistry`.

## Resolver Client Boundary

Resolvers may need small backend clients to turn an address into something the
caller can use. This should be part of the shared resource layer, not repeated
inside configuration, ACL, bootstrap, or secret code.

The initial client set should be deliberately minimal:

- HTTP/HTTPS: read bytes from a concrete URL with timeouts, size limits,
  redirect policy, and safe error reporting;
- S3-compatible storage: read an object by bucket/key and later expose a
  writable object-store adapter when the caller asks for write capability;
- Git: read a single path from a repository/ref without forcing feature code to
  clone or understand Git transport details, and expose a versioned writable
  store when the caller needs read-write semantics.

The resolver should hide these clients behind typed results. A caller that asks
for configuration content or a secret receives content bytes plus metadata. A
caller that asks for ACL storage receives a storage abstraction backed by local
directory, S3, Git, or another backend, and that abstraction can be read-write.

Feature code should not receive raw HTTP, S3, or Git clients unless the feature
explicitly is a transport feature. Most callers should depend on one of these
forms:

```text
read-only content: bytes/stream + source metadata
read-write content store: load/save/delete/list as needed
versioned content store: load/save with version or commit metadata
repository provider: backend-specific repository access
```

## Resolution Rules

- Resolve from the innermost reference provider outward.
- `env:` returns the value of an environment variable.
- `content:` returns inline content or bytes.
- `content:base64,...` returns decoded bytes.
- Outer schemes define the expected backend type.
- If an inner resolved value already has the same concrete scheme as the outer
  scheme, keep it as-is.
- If an inner resolved value has a conflicting concrete scheme, fail unless a
  backend explicitly supports that coercion.
- Apply phase and capability checks after dereferencing.
- Enforce a max dereference depth, initially 5.
- Detect direct cycles where possible.
- Never log secret dereferenced values.

Examples:

```text
file:env:ORION_CONFIG
  env -> /etc/orion/orion.yml
  result -> file:/etc/orion/orion.yml

s3:env:ORION_ACL
  env -> s3://bucket/bootstrap/orion.xml
  result -> s3://bucket/bootstrap/orion.xml

git+ssh:env:ORION_CONFIG_REPO
  env -> git+ssh://git@example.test/orion/config.git?path=orion.yml
  result -> git+ssh://git@example.test/orion/config.git?path=orion.yml

file:env:ORION_CONFIG
  env -> s3://bucket/orion.yml
  result -> error, because file cannot coerce an s3 address
```

## Bootstrap Safety

Nested references do not bypass bootstrap restrictions.

For application configuration resolution:

```text
phase = PRE_CONFIGURATION
```

If dereferencing produces `local:...`, resolution must fail because internal
Orion storage is not initialized yet.

Example:

```text
file:env:ORION_CONFIG
  env -> local:config/orion.yml
  result -> error
```

The error should name the original reference and the resolved scheme without
printing secret values:

```text
Resource reference file:env:ORION_CONFIG resolves to local: storage, which
cannot be used before Orion runtime storage is initialized
```

## Security and Logging

`ResourceReference` should preserve a safe display form.

Safe:

```text
file:env:ORION_CONFIG
s3:env:ORION_ACL
content:<redacted>
content:base64,<redacted>
```

Unsafe:

```text
expanded environment variable value
inline secret content
keystore password
private key content
```

The resolver should return both the resolved address and safe diagnostics:

```java
record ResolvedResourceAddress(
        ResourceReference original,
        ResourceAddress address,
        String safeDisplayName) {
}
```

## Migration Plan

1. Add `ResourceReference` and `ResourceAddress` alongside existing
   `ResourceLocation`.
2. Move parsing tests from `ResourceLocationTest` into the new names.
3. Add nested reference tests for `file:env:...`, `s3:env:...`, and conflicting
   schemes.
4. Introduce `ResourceReferenceResolver` with `env:` and `content:` providers.
5. Teach the unified resource storage resolver to accept `ResourceReference`
   and use `ResourceAddress` internally.
6. Replace feature code imports from `ResourceLocation` to `ResourceAddress`.
7. Keep a temporary adapter from `ResourceLocation` to `ResourceAddress` only
   while migration is in progress.
8. Remove `ResourceLocation` after all callers move.

## Test Matrix

Cover at least these cases:

- plain path becomes a file-like empty-scheme address;
- `file:/tmp/orion.yml` parses as file address;
- `file:env:ORION_CONFIG` resolves to a file address from the environment;
- `file:env:ORION_CONFIG` fails if the environment variable is missing;
- `file:env:ORION_CONFIG` fails if the environment value is an incompatible
  `s3:` address;
- `s3:env:ORION_ACL` resolves when the environment contains an S3 URI;
- `git+ssh:env:ORION_CONFIG_REPO` resolves when the environment contains a
  Git URI;
- nested references obey max depth;
- nested references cannot produce `local:` during `PRE_CONFIGURATION`;
- safe display strings never include expanded environment values or inline
  content.

## Relationship to Unified Storage Resolution

`ResourceReference` and `ResourceAddress` are the input model for the unified
storage resolver.

The storage resolver should not parse nested schemes itself. It should receive a
resolved `ResourceAddress` and then select a backend based on usage, phase, and
capabilities.
