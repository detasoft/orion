# Key Material Keystore and Internal CA

## Goal

Unify Orion private key and certificate storage behind one key material layer.

The first targets are the existing SSH host keys, server signing key, HTTPS
certificate material, and ACME account/domain keys. The design should also make
it possible to add an Orion-owned certificate authority for issuing certificates
to users and internal services.

Breaking the current file layout is acceptable. The new design does not need to
preserve `baseDir/ssh-host-keys`, `baseDir/server-identity`, or `acme/*.keypair`
as primary storage.

The primary writable runtime format is a single `PKCS12` keystore. PEM and
PKCS#8 stay important as import/export and legacy migration formats, but they are
not the default storage layout.

## Requirements

- Store private keys and certificates in a standard keystore format that can be
  opened by external tools.
- Use `PKCS12` as the default format because it is supported by Java `keytool`,
  OpenSSL, and common operations tooling.
- Treat OpenSSL compatibility as a first-class requirement: Orion-created
  keystores must be inspectable with `openssl pkcs12` and `keytool`.
- Do not use a custom PEM directory as the primary storage format. PEM/PKCS#8
  support belongs in import/export, compatibility reads, and migration commands.
- Keep `JKS` as a compatibility option where useful, but do not make it the
  default.
- Consider `BCFKS` only as a later hardening option if the project decides to
  depend on Bouncy Castle keystore tooling explicitly.
- Protect the keystore with a configured password.
- Support distinct aliases for each key purpose.
- Start with local-file and in-memory stores. Add S3, Git-backed, and other
  remote writable stores only after the shared resource storage boundary is ready.
- Allow key material storage to become local, remote, or inline through the same
  resource-reference concept used elsewhere in Orion.
- Make rotation explicit: old verification keys must remain available while new
  keys become active.
- Never store user private keys when issuing user certificates. Users should
  submit a CSR or public key; Orion signs and stores only public certificate
  metadata.

## Configuration Sketch

```yaml
security:
  keyStore:
    type: PKCS12
    location: env:ORION_KEYSTORE_LOCATION
    password: env:ORION_KEYSTORE_PASSWORD
    createIfMissing: true

  keys:
    serverSigning:
      activeAlias: server-signing-2026-05
      verifyAliases:
        - server-signing-2026-04

    sshHost:
      aliases:
        - ssh-host-rsa-2026-05
        - ssh-host-ecdsa-2026-05

    https:
      activeAlias: https-2026-05
      nextAlias: https-2026-08

    acme:
      accountAlias: acme-account
      domainAlias: acme-domain

  ca:
    enabled: true
    issuerAlias: orion-ca-2026-05
    certificateAlias: orion-ca-cert-2026-05
```

## Resource Locations

Use one resolver for keystore locations, passwords, and inline content. The
resolver should use Orion's shared resource-addressing model and must not add
another ad hoc parser. While the repository still has both the older
`ResourceLocation`/`ResourceScheme` utilities and the newer
`core/resource-addressing` module, key material should either use the shared
module directly or go through a temporary compatibility adapter.

Supported value forms:

- `/path/to/orion.p12`: local path, represented as an empty scheme.
- `file:/path/to/orion.p12`: local file URI.
- `env:ORION_KEYSTORE_LOCATION`: read the location or secret value from an
  environment variable.
- `content:...`: inline text content.
- `content:base64,...`: inline binary content, including a full keystore.
- `s3://bucket/path/orion.p12`: later object-storage-backed keystore.
- `git+file:...`, `git+ssh://...`, `git+http://...`, `git+https://...`: later
  Git-backed keystore file.
- `local:...`: later Orion local versioned storage when a caller intentionally
  wants repository-backed local storage.
- Any future scheme supported by Orion's resource-addressing infrastructure
  should be pluggable through the same `ResourceContentStore`
  boundary.

Classpath resources should remain read-only and are useful for tests or bundled
defaults, but not for writable production keystores.

The first implementation should support plain paths, `file:`, `env:` for
locations and passwords, and `content:base64,...` for read-only test/bootstrap
input. Writable S3 and Git stores are follow-up work.

## Storage Boundary

Introduce a small storage abstraction:

```text
ResourceContentStore.read(location) -> bytes
ResourceContentStore.write(location, bytes, expectedVersion) -> version
```

Concrete stores:

- local path/file store for the first writable implementation;
- S3 object store as a follow-up;
- remote Git single-file store as a follow-up;
- local Orion versioned store as a follow-up;
- content/env read-only stores for bootstrap input;
- in-memory store for tests.

Local files should write atomically through a temporary file plus move. Remote
writable stores must provide concurrency behavior before they are enabled for
production key material. For Git this means a commit/push with non-fast-forward
conflict handling. For S3 this means ETag or version checks when available.

## Key Material API

Introduce `KeyMaterialService` as the only service that opens, creates, saves,
and rotates keystore entries.

Core operations:

```text
getKeyPair(alias)
getPrivateKey(alias)
getCertificateChain(alias)
getTrustedCertificate(alias)
getActiveSigningKey(purpose)
getVerificationKeys(purpose)
generateKeyIfMissing(alias, spec)
setPrivateKey(alias, keyPair, certificateChain)
setTrustedCertificate(alias, certificate)
rotate(purpose, newAlias)
save()
```

The service should keep passwords as `char[]` or bytes internally and must never
log secret values.

## Standard Keystore Entries

Use standard Java keystore entries wherever possible:

- `PrivateKeyEntry` for server signing, HTTPS, ACME, SSH host private keys, and
  CA issuer keys.
- `TrustedCertificateEntry` for CA roots, intermediate certificates, and pinned
  external certificates.
- `SecretKeyEntry` only if the project later needs symmetric secrets in the same
  keystore.

`PKCS12` private key entries expect a certificate chain. HTTPS keys and CA issuer
keys naturally have real X.509 certificate chains. Other Orion-owned private
keys, such as server signing keys, ACME account keys, and SSH host keys, need a
generated self-signed storage certificate so they can be stored as standard
`PrivateKeyEntry` values.

Storage certificates are not trust anchors and are not used by the runtime
protocol unless that protocol explicitly needs an X.509 certificate. Their
subject and extensions should clearly identify the alias, purpose, and the fact
that the certificate exists only to make the `PKCS12` entry standard and
tool-readable. The SSH server still derives the SSH public key from the private
key. Server token verification still uses the configured signing public keys, not
the storage certificate as a certificate authority.

## Service Migration

Replace direct file ownership in current services:

- `ServerIdentityKeyService` reads active and verify-only signing keys from
  `KeyMaterialService`.
- `SshHostKeyService` reads configured host key aliases from `KeyMaterialService`.
- `JettyHTTPServer` reads HTTPS private key and certificate chain from
  `KeyMaterialService`.
- `AcmeCertificateService` reads/writes account and domain keys through
  `KeyMaterialService`.

Current file paths can be removed from the primary runtime path. A one-time
import command should handle existing installations by reading PEM/PKCS#8 files
from `baseDir/server-identity`, `baseDir/ssh-host-keys`, and `acme/*.keypair`
and writing the corresponding aliases into the `PKCS12` keystore.

## Rotation Model

Server signing keys:

- `activeAlias` signs new tokens.
- `verifyAliases` remain valid for existing tokens until token TTL expires.
- retired aliases are removed from configuration and optionally from the
  keystore.

SSH host keys:

- configured aliases are all served by SSHD.
- rotation is a two-step config change: add new alias, then remove old alias
  after clients have learned the new host key.

HTTPS certificates:

- `activeAlias` is used by Jetty.
- `nextAlias` can be pre-issued by ACME or the internal CA.
- first implementation can require restart for activation; hot reload can be
  added later.

CA issuer keys:

- CA roots and intermediates should rotate slowly.
- old issuer certificates must stay available for verification until all issued
  certificates expire or are revoked.

## Internal Certificate Authority

This keystore design is enough to build an Orion certificate authority because
it provides the missing secure holder for the CA private key and certificate
chain.

The keystore is not the CA database. It should store issuer private keys,
issuer certificate chains, trusted CA certificates, and optionally pinned
external certificates. Issued-certificate records, serial numbers, ownership,
revocation state, and policy decisions belong in a separate CA registry.

Additional CA pieces are still required:

- `CertificateAuthorityService` that loads `issuerAlias` from `KeyMaterialService`;
- CA initialization command to create a root or intermediate CA certificate;
- CSR-based certificate issue flow;
- issued-certificate registry with serial number, subject, SANs, owner, expiry,
  status, and fingerprint;
- revocation support through a CRL first, with OCSP as a later option;
- public endpoint for CA certificate chain download;
- admin endpoints or commands for issue, renew, revoke, and list;
- policy checks that bind certificate issuance to ACL users, groups, roles, or
  configured domains.

Initial CA scope should be X.509 certificates for users, services, HTTPS, DTLS,
and internal Orion clients. OpenSSH user certificates are a different certificate
format and should be a separate follow-up feature if needed.

## Implementation Order

1. Add `SecurityConfig`, `KeyStoreConfig`, key alias config classes, and
   configuration schema tests.
2. Add local-file and in-memory `ResourceContentStore` implementations with
   atomic local writes.
3. Add minimal resource reference resolution for local paths, `file:`, `env:`,
   and `content:base64,...` without introducing another parser.
4. Add `KeyMaterialService` with `PKCS12` load/create/save and unit tests.
5. Add storage-certificate generation for non-X.509 private key purposes.
6. Move `ServerIdentityKeyService` to active and verify-only aliases in
   `KeyMaterialService`.
7. Move `SshHostKeyService` to configured host key aliases in
   `KeyMaterialService`.
8. Move HTTPS to a configured `PKCS12` alias while keeping legacy PEM/JKS config
   as a compatibility path during migration.
9. Move ACME account and domain key reads/writes to `KeyMaterialService`.
10. Add PEM/PKCS#8 import/export and one-time migration command for existing key
    files.
11. Add rotation tests for signing, SSH host keys, and HTTPS aliases.
12. Add S3 and Git-backed `ResourceContentStore` implementations after the
    shared resource storage boundary is ready.
13. Add `CertificateAuthorityService`, CA registry storage, and CA
    issue/revoke/list flows.

## Verification

Cover at least these cases:

- PKCS12 keystore created by Orion can be opened with Java `keytool`.
- PKCS12 keystore created by Orion can be inspected with `openssl pkcs12`.
- keystore password can come from `env:` and `file:`.
- keystore bytes can be loaded from `content:base64,...`.
- local path and `file:` locations round-trip atomically.
- non-X.509 key purposes are stored with storage certificates and can be loaded
  back as private keys.
- PEM/PKCS#8 legacy keys can be imported into a `PKCS12` keystore.
- server signing rotation signs with active alias and verifies active plus
  verify-only aliases.
- SSH host key service serves all configured aliases.
- HTTPS starts from a configured keystore alias.
- ACME account and domain keys persist through `KeyMaterialService`.
- S3-backed keystore round-trips through a fake S3 unit test and MinIO
  integration test when the remote store follow-up is implemented.
- Git-backed keystore detects conflicting remote updates when the remote store
  follow-up is implemented.
- CA can issue a certificate from a CSR and the resulting certificate validates
  against the exported CA chain.
