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
