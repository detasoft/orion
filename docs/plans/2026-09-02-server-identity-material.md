# Server Identity Material Implementation Plan

> Execute this plan in the dedicated `server-identity-migration` task worktree.

## Goal

Make the protected key-material store the only runtime owner of Orion server
identity keys. JWT issuance signs through a typed capability, JWT verification
selects one retained key by the exact material alias carried in `kid`, and the
active public identity key remains available for root SSH enrollment. Retained
signing keys are revoked from root SSH while they remain valid for JWT
verification. There is no
legacy PEM read, import, fallback, or migration path.

## Contracts

- Add one `ServerIdentityCapability` to `core/key-material` exposing only:
  the active alias, signing, exact-alias verification, and public verification
  keys. It never exposes private keys.
- Build the capability from a `SigningMaterialSet`. The active descriptor must
  be RSA `SERVER_SIGNING`; retained descriptors must be compatible and older.
- Use the active material alias verbatim as JWT `kid`.
- Resolve verification by exact `kid`; do not scan all keys and do not derive
  identifiers from public-key fingerprints.
- Opening a missing store may create and persist the configured active key.
  An existing store with a missing, wrong-purpose, wrong-algorithm, or
  metadata-mismatched entry fails without generating a replacement.
- Retained verification entries must already exist. Reloading the same store
  reconstructs the same active and retained identity.

## Bootstrap configuration

Add bootstrap-only configuration for the material location, password
reference, store creation policy, cluster identifier, and server-signing
descriptor set. Relative material locations resolve below `bootstrap.baseDir`.
Passwords use the existing protected resource resolver (`env:` or protected
`file:`); plaintext and inline passwords remain rejected.

Defaults describe a new installation:

- location: `key-material/orion.p12`;
- password: `env:ORION_KEY_MATERIAL_PASSWORD`;
- active alias: `server-signing-v1`;
- active version: `1`;
- algorithm: RSA;
- cluster scope: configured cluster identifier;
- no retained aliases.

The application opens the server identity before constructing the runtime
component, passes only `ServerIdentityCapability` into Dagger, and closes the
material owner after runtime shutdown. Tests that construct a component but do
not exercise identity operations may bind the explicit unavailable capability.

## Implementation sequence

1. Add capability tests in `core/key-material` for active alias signing,
   exact-alias verification, retained-key verification after reload, unknown
   alias rejection, and non-RSA rejection.
2. Implement `ServerIdentityCapability` and a closeable material-backed owner
   that performs new-store initialization and strict existing-store loading.
3. Add focused tests proving an existing store never receives a generated
   replacement for a missing configured alias.
4. Replace `ServerKeySigner`, `PublicKeysProvider`, and the file-owning
   `ServerIdentityKeyService` with the new capability in ACL/JWT code.
5. Add JWT tests proving `kid` equals the active alias, retained aliases verify,
   unknown aliases fail, and rotation changes issuance without invalidating
   retained tokens.
6. Add schema/bootstrap configuration and a factory that resolves the local
   store and protected password reference into a closeable identity owner.
7. Bind the capability into `OrionComponent`; make `App` own its lifetime.
8. Update integration support to create test material explicitly. Replace every
   read of `server-identity/signing-rsa.pem` with the key from the test material
   store and assert the legacy directory is absent.
9. Remove the legacy service and its runtime providers, update help examples,
   and search production code for the old path and file ownership.

## Verification

- Focused RED/GREEN tests for `core/key-material`, `core/acl`, `core/schema`,
  and `core/bootstrap` with reactor dependencies.
- Compile integration-test sources after updating component construction.
- Run `mvn verify -Pdev -T 4` and `git diff --check`.
- Request independent code review against `docs/reviews/RULES.md`.
- Finish as one task commit, remove this leaf from the task tree, cherry-pick to
  `main`, run `make test`, and remove the task worktree and branch.
