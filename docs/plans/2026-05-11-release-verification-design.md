# Release Verification Design

## Goal

Orion release artifacts should be independently verifiable with standard GPG
tooling, while the self-executable jar also provides a convenient `verify`
command for operators.

## Trust Model

The authoritative verification path is external:

```sh
gpg --verify orion.jar.asc orion.jar
```

The embedded `orion.jar verify` command is a convenience wrapper, not the sole
trust anchor. If an attacker can replace the jar, they can also replace the
embedded verification code. Strict verification relies on GPG checking a
detached signature against a trusted DETA PRO B.V. release key fingerprint.

## Artifact Layout

Release publishing should produce:

- `bootstrap-<version>-executable.jar`
- `bootstrap-<version>-executable.jar.asc`
- `bootstrap-<version>-executable.jar.sha256`

The `.asc` file is an armored detached GPG signature over the exact executable
jar bytes. The `.sha256` file is a convenience checksum and is not a substitute
for the signature.

## Network Key Retrieval

The Java `verify` command may download the public release key from:

```text
https://www.deta-it.com/.well-known/orion/release.asc
```

The downloaded key is trusted only after its fingerprint matches an expected
fingerprint. The expected fingerprint can be embedded later once the production
release key exists, or supplied by the operator through `--fingerprint` or
`ORION_RELEASE_KEY_FINGERPRINT`.

The signature is read from a local sibling file by default:

```text
<orion.jar>.asc
```

Operators can override it with `--signature` or `ORION_RELEASE_SIGNATURE` for a
local path, or `--signature-url` or `ORION_RELEASE_SIGNATURE_URL` for a network
URL.

## Runtime Behavior

The POSIX launcher should keep LSB metadata, resolve the current artifact path,
and pass all command line arguments into Java:

```sh
exec "$java" $JAVA_OPTS -jar "$SELF" "$@"
```

Service process management and release verification both live in Java, not in
the shell launcher.

The Java command should:

1. Require `gpg`.
2. Create a temporary `GNUPGHOME`.
3. Load the release public key from `--key`, `ORION_RELEASE_PUBLIC_KEY`,
   `--key-url`, or `ORION_RELEASE_PUBLIC_KEY_URL`.
4. Check the key fingerprint against `--fingerprint` or
   `ORION_RELEASE_KEY_FINGERPRINT`.
5. Load the detached signature from `--signature`, `ORION_RELEASE_SIGNATURE`, a
   sibling `<jar>.asc`, `--signature-url`, or `ORION_RELEASE_SIGNATURE_URL`.
6. Run `gpg --verify <signature> <jar>`.
7. Remove temporary key material before exit.

The command should fail closed when no expected fingerprint is configured.

## Release Signing

The build can create `.sha256` during packaging, but GPG signing should be a
release step because the private release key must not live in the repository.
The documented signing command is:

```sh
gpg --armor --detach-sign \
  core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar
```

The release process can later wrap this in CI once the signing key storage is
defined.
