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

## Requirements

- Store private keys and certificates in a standard keystore format that can be
  opened by external tools.
- Use `PKCS12` as the default format because it is supported by Java `keytool`,
  OpenSSL, and common operations tooling.
- Keep `JKS` as a compatibility option where useful, but do not make it the
  default.
- Consider `BCFKS` only as a later hardening option if the project decides to
  depend on Bouncy Castle keystore tooling explicitly.
- Protect the keystore with a configured password.
- Support distinct aliases for each key purpose.
- Allow key material storage to be local, remote, or inline through the same
  resource-location concept used elsewhere in Orion.
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

Use one resolver for keystore locations, secrets, and inline content. The
resolver should be based on `ResourceLocation`/`ResourceScheme`, not on ad hoc
string parsing.

Supported value forms:

- `/path/to/orion.p12`: local path, represented as an empty scheme.
- `file:/path/to/orion.p12`: local file URI.
- `env:ORION_KEYSTORE_LOCATION`: read the location or secret value from an
  environment variable.
- `content:...`: inline text content.
- `content:base64,...`: inline binary content, including a full keystore.
- `s3://bucket/path/orion.p12`: object storage backed keystore.
- `git+file:...`, `git+ssh://...`, `git+http://...`, `git+https://...`: Git
  backed keystore file.
- `local:...`: Orion local versioned storage when a caller intentionally wants
  repository-backed local storage.
- Any future `ResourceScheme.Other` supported by Orion's resource-location
  infrastructure should be pluggable through the same `ResourceContentStore`
  boundary.

Classpath resources should remain read-only and are useful for tests or bundled
defaults, but not for writable production keystores.

## Storage Boundary

Introduce a small storage abstraction:

```text
ResourceContentStore.read(location) -> bytes
ResourceContentStore.write(location, bytes, expectedVersion) -> version
```

Concrete stores:

- local path/file store;
- S3 object store;
- remote Git single-file store;
- local Orion versioned store;
- content/env read-only stores for bootstrap input;
- in-memory store for tests.

Remote writable stores must provide concurrency behavior. For Git this means a
commit/push with non-fast-forward conflict handling. For S3 this means ETag or
version checks when available. Local files should write atomically through a
temporary file plus move.

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

SSH host keys are not X.509 certificates, but `PKCS12` private key entries expect
a certificate chain. Store SSH host keys as private key entries with a generated
self-signed storage certificate whose subject and extensions clearly identify the
entry as an SSH host key. The SSH server still derives the SSH public key from
the private key; the storage certificate is only there to keep the keystore
standard and tool-readable.

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
import command may be added later if existing installations need migration.

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
2. Add resource reference resolution for path, secret, and content values.
3. Add `ResourceContentStore` with local file and in-memory implementations.
4. Add S3 and Git-backed `ResourceContentStore` implementations by reusing the
   existing S3 and remote Git location patterns.
5. Add `KeyMaterialService` with PKCS12 load/create/save and unit tests.
6. Move `ServerIdentityKeyService` to `KeyMaterialService`.
7. Move `SshHostKeyService` to `KeyMaterialService`.
8. Move HTTPS and ACME key reads/writes to `KeyMaterialService`.
9. Add rotation tests for signing, SSH host keys, and HTTPS aliases.
10. Add `CertificateAuthorityService` and CA issue/revoke/list flows.

## Verification

Cover at least these cases:

- PKCS12 keystore created by Orion can be opened with Java `keytool`.
- keystore password can come from `env:` and `file:`.
- keystore bytes can be loaded from `content:base64,...`.
- local path and `file:` locations round-trip atomically.
- S3-backed keystore round-trips through a fake S3 unit test and MinIO
  integration test.
- Git-backed keystore detects conflicting remote updates.
- server signing rotation signs with active alias and verifies active plus
  verify-only aliases.
- SSH host key service serves all configured aliases.
- HTTPS starts from a configured keystore alias.
- ACME account and domain keys persist through `KeyMaterialService`.
- CA can issue a certificate from a CSR and the resulting certificate validates
  against the exported CA chain.
