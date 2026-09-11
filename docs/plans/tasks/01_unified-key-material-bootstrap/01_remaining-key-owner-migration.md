# Migrate the Remaining Orion Key Owners

Status: todo
Depends on: completed typed-material-capabilities (f540dd1e),
completed server-identity-migration (6679da3b)

Stop Orion-owned private keys from being scattered across independent files.

## Required Result

- SSH host keys come only from the protected material store through
  `SshHostKeyCapability`.
- HTTPS private keys and chains and ACME account/domain keys continue to use
  their existing typed material capabilities.
- CA issuer keys remain represented by `CertificateAuthorityCapability`; no CA
  runtime owner exists yet.
- SSH host keys, HTTPS, ACME, server identity, and future CA issuer material are
  cluster-wide. A node that needs its own SSH identity selects explicit logical
  host-key aliases in its transport configuration.
- Existing PEM and keypair files are not imported. The legacy files and their
  active runtime paths are removed.

## Design

- The material owner discovers standard PKCS12 entries by typed purpose and
  cluster scope. With no explicit transport references, SSH receives every
  `SSH_HOST` entry; if none exist, standard RSA and EC entries are created.
- `transport.ssh.hostKeys` optionally selects ordered logical aliases. Concrete
  PKCS12 aliases use `<logical-alias>-v<version>`. A concrete alias selects its
  exact entry; otherwise the reference is a logical alias and selects its
  highest stored version.
- SSH transport receives only `SshHostKeyCapability`. Restart reloads typed
  entries; adding a new concrete version and later removing the old one is the
  rotation mechanism.
- A missing, invalid, wrong-purpose, or wrong-scope descriptor fails startup.
  No compatibility read, dual-write path, or migration command is retained.

## Implementation Plan

1. Add optional ordered logical SSH host-key references to transport schema and
   parsing tests.
2. Discover, generate, and reload cluster-scoped SSH host keys through
   `OrionKeyMaterial`, with tests for multiple algorithms, fallback selection,
   concrete aliases, and latest-version resolution.
3. Bind `SshHostKeyCapability` into runtime composition, switch the SSH
   transport consumer, and delete `SshHostKeyService` plus PEM fixtures.
4. Verify focused behavior and the full JVM suite, review the complete change,
   then remove this completed leaf.

## Acceptance

- A fresh material store contains server identity and default SSH host keys.
- Reopening the store returns the same ordered SSH public keys.
- Empty transport references select every typed cluster SSH host key; explicit
  references select exact concrete aliases or the latest version of logical
  aliases.
- SSH transport starts with those keys and never creates or reads
  `baseDir/ssh-host-keys`.
- No production reference to legacy HTTPS, ACME keypair, or SSH host-key file
  storage remains.
- Full required verification passes.
