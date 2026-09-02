# Configuration Secrets and Server Identity Design

## Goal

Make the protected material store the only owner of configuration wrapping
keys and Orion server signing keys. Configuration snapshots carry authenticated
encrypted envelopes, while runtime consumers receive only purpose-specific
cryptographic capabilities.

There are no active installations to migrate. The implementation does not
read, import, retain, or fall back to the legacy `server-identity` PEM files.

## Boundary

`KeyMaterialService` continues to own the PKCS12 store and raw key objects. It
must not be injected into configuration, JWT, or other application services.
`KeyMaterialCapabilities` validates configured descriptors and exposes only the
operations allowed by their purpose.

The work has two independently deliverable slices:

1. configuration envelope encryption and validation;
2. server signing and verification backed by configured material aliases.

The versioned Orion XML schema remains a separate task. This work supplies the
strict envelope model and parser that its secret fields must use, without
introducing the future hierarchy schema early.

## Configuration Secret Context

Every encrypted value is bound to a typed context containing:

- stable secret identifier;
- secret kind.

The context deliberately excludes configuration schema versions, organization,
team, repository, storage location, and resolver details. An owning
configuration object holds or resolves a secret reference; the secret and the
cryptographic capability do not point back to that owner or know where the
value came from.

Context is encoded as a canonical length-prefixed UTF-8 byte sequence with
its own format version and fixed field order. It is passed to the authenticated
cipher as additional authenticated data and is not stored as secret material.

The same secret reference can therefore be resolved independently of its
configuration hierarchy. Substituting an envelope for a different secret
identifier or kind causes authenticated decryption to fail.

## Configuration Secret Envelope

Sealing generates a fresh 256-bit AES data-encryption key for every value. The
plaintext is encrypted with AES-256-GCM using a fresh 96-bit nonce and the
canonical context as additional authenticated data. The configured
purpose-scoped AES wrapping key wraps the data key with AES Key Wrap.

The immutable envelope contains:

- envelope format version;
- wrapping-key alias and material version;
- key-wrapping algorithm;
- data-encryption algorithm;
- binary encoding identifier;
- wrapped data key;
- GCM nonce;
- ciphertext including the authentication tag.

Binary fields use unpadded base64url in the serialized representation. The
codec is deterministic and strict: it rejects missing, duplicate, reordered,
unknown, empty, or non-canonical fields. Unknown versions, encodings, and
algorithms are incompatible instead of being guessed.

`ConfigurationCipherCapability` accepts the plaintext and typed context when
sealing, and the envelope plus the expected context when opening. Neither the
wrapping key nor the generated data key crosses the capability boundary. The
implementation clears temporary plaintext and data-key byte arrays where Java
ownership permits it.

Configuration validation accepts only a successfully parsed envelope for a
secret-valued field. Plain text is not interpreted as an implicit secret and
cannot reach candidate preparation. Opening also checks that the envelope
alias and version match a registered `CONFIGURATION_CIPHER` descriptor with
the correct scope.

## Server Identity

The file-owning `ServerIdentityKeyService` is replaced with an adapter over an
active `SigningCapability` and retained `VerificationCapability` instances.
There is no compatibility read, import command, automatic key generation, or
filesystem fallback.

The active configured alias becomes the JWT `kid`. Issuance asks the active
capability to sign the exact JWT signing input. Verification resolves `kid` to
one configured active or retained alias and asks only that capability to verify
the signature. Application code receives no private key and does not iterate
over raw public keys.

Reload and rotation build a complete immutable identity view before
publication:

- one active `SERVER_SIGNING` descriptor signs new values;
- the active descriptor and older compatible descriptors verify values;
- aliases are unique and exact, and all descriptors have one purpose,
  algorithm, and cluster scope;
- a missing entry, wrong purpose, wrong scope, wrong version, or incompatible
  algorithm prevents activation.

Switching the active alias therefore affects only new signatures. Retained
aliases continue to verify existing JWTs across material-store reloads and
process restarts.

## Failure Handling

Cryptographic operations expose safe typed failures or
`GeneralSecurityException` causes at the capability boundary. Diagnostics may
name the envelope version, algorithm, alias, material version, and context
field that is invalid. They must not contain plaintext, key bytes, ciphertext,
nonces, signatures, tokens, or material-store credentials.

Candidate preparation maps failures to the established bootstrap categories:

- missing alias or entry: `MISSING`;
- unsupported envelope or algorithm: `INCOMPATIBLE`;
- purpose, scope, version, or context mismatch: `MISMATCHED`;
- malformed or unauthentic envelope: `CORRUPT`;
- inaccessible material store: `UNAVAILABLE`.

A failed candidate is never published, and reload retains the previous active
runtime projection.

## Verification

Configuration cryptography tests cover:

- seal/open round trips and material-store reload;
- fresh data keys and nonces for repeated plaintext;
- secret identifier and kind as authenticated data;
- ciphertext, nonce, wrapped-key, alias, and version tampering;
- wrong-purpose, wrong-scope, unavailable, and retired wrapping material;
- strict serialization, unsupported versions and algorithms, and plaintext
  rejection;
- absence of raw key-returning methods on the public capability.

Server identity tests cover:

- signing with the active alias and verification by exact `kid`;
- retained verification aliases before and after restart;
- rotation changing the issuing alias without invalidating retained aliases;
- missing, conflicting, wrong-purpose, wrong-scope, and wrong-version material;
- JWT issue and verification through capability-backed interfaces;
- bootstrap wiring without any legacy filesystem dependency.

Each slice is developed test-first in its own task worktree. After focused
tests and full development verification pass, its task commit is squashed,
transferred to `main` by cherry-pick, verified on `main`, and its worktree and
branch are removed before the task is reported complete.
