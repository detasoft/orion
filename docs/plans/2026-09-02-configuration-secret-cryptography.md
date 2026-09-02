# Configuration Secret Cryptography Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add strict, context-bound envelope encryption for secret values
stored in versioned Orion configuration.

**Architecture:** Extend the existing typed material capability boundary
instead of exposing `KeyMaterialService`.
Each secret gets a fresh AES-256 data key, AES-GCM encryption with canonical context as AAD, and AES Key Wrap
under a registered `CONFIGURATION_CIPHER` material alias. A strict named-field
base64url codec supplies the future
`orion.xml` schema with a value type that rejects plaintext and unsupported formats before activation.

**Tech Stack:** Java 21 records and sealed enums, JCA/JCE (`AESWrap`, `AES/GCM/NoPadding`), JUnit 5, AssertJ,
Maven `dev` profile.

---

### Task 1: Define and authenticate the secret context

**Files:**
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretContext.java`
- Create: `core/key-material/src/test/java/pro/deta/orion/keymaterial/ConfigurationSecretContextTest.java`

**Step 1: Write the failing validation and canonicalization tests**

Add tests that construct:

```java
ConfigurationSecretContext context = new ConfigurationSecretContext(
        2, "acme", "platform", "api", "github-token", "access-token");
```

Assert that equal contexts produce equal canonical bytes, every changed component produces different bytes,
absent hierarchy levels differ from empty strings, and delimiter-like values cannot collide. Assert that schema
version, secret ID, and kind must be present, and that repository requires
team while team requires organization.

**Step 2: Run the test and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/key-material -am \
  -Dtest=ConfigurationSecretContextTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `ConfigurationSecretContext` does not exist.

**Step 3: Implement the minimal immutable context**

Create a public record with this API:

```java
public record ConfigurationSecretContext(
        int schemaVersion,
        String organization,
        String team,
        String repository,
        String secretId,
        String kind) {
    public byte[] authenticatedBytes();
}
```

Validate the hierarchy and required fields in the compact constructor. Encode a fixed context-format marker,
the schema version, and every nullable UTF-8 field with a four-byte length (`-1` for absent) through
`DataOutputStream`. Return a new byte array on every call.

**Step 4: Run the test and verify GREEN**

Run the command from Step 2. Expected: PASS.

**Step 5: Commit**

```bash
git add core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretContext.java \
  core/key-material/src/test/java/pro/deta/orion/keymaterial/ConfigurationSecretContextTest.java
git commit -m "Define authenticated configuration secret context"
```

### Task 2: Define a strict versioned envelope representation

**Files:**
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretEnvelope.java`
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretException.java`
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretEnvelopeCodec.java`
- Create: `core/key-material/src/test/java/pro/deta/orion/keymaterial/ConfigurationSecretEnvelopeCodecTest.java`

**Step 1: Write failing envelope and codec tests**

Use a deterministic fixture:

```java
ConfigurationSecretEnvelope envelope = new ConfigurationSecretEnvelope(
        1,
        new KeyMaterialAlias("configuration-v1"),
        new KeyMaterialVersion(1),
        "AESWrap",
        "AES/GCM/NoPadding",
        "base64url",
        bytes(1, 2, 3),
        twelveByteNonce(),
        bytes(4, 5, 6));
```

Assert deterministic serialize/parse round-trip and defensive array copies. Assert rejection of plaintext,
wrong field count, empty fields, padded or otherwise non-canonical base64url, invalid numbers, unknown version,
unknown algorithms, and unknown encoding. Verify failures expose only a typed reason and safe structural text.

**Step 2: Run the tests and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/key-material -am \
  -Dtest=ConfigurationSecretEnvelopeCodecTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the envelope types do not exist.

**Step 3: Implement the immutable envelope and typed failure**

Give `ConfigurationSecretEnvelope` the exact fields used by the fixture,
validate positive versions and non-empty metadata/binary fields, and
defensively copy every byte array. Define:

```java
public final class ConfigurationSecretException extends GeneralSecurityException {
    public enum Reason {
        MALFORMED,
        UNSUPPORTED,
        MATERIAL_MISMATCH,
        AUTHENTICATION_FAILED
    }

    public Reason reason();
}
```

Messages may name a field, version, algorithm, alias, or material version, but never include encoded envelope
bytes.

**Step 4: Implement the strict codec**

Expose:

```java
public final class ConfigurationSecretEnvelopeCodec {
    public String serialize(ConfigurationSecretEnvelope envelope)
            throws ConfigurationSecretException;

    public ConfigurationSecretEnvelope parse(String value)
            throws ConfigurationSecretException;
}
```

Use ten dot-separated named fields in fixed order:

```text
orion-secret.version=<version>.alias=<alias>.key-version=<material-version>
.wrap=<wrap-algorithm>.cipher=<data-algorithm>.encoding=<encoding>
.wrapped-key=<wrapped-key>.nonce=<nonce>.ciphertext=<ciphertext>
```

Encode alias, algorithm names, encoding, and binary fields as unpadded
base64url UTF-8/bytes. Parse with `split("\\.", -1)`, require each expected
field name at its exact position, validate supported constants, reserialize the
parsed value, and require exact equality to reject non-canonical input.

**Step 5: Run the tests and verify GREEN**

Run the command from Step 2. Expected: PASS.

**Step 6: Commit**

```bash
git add core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretEnvelope.java \
  core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretException.java \
  core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationSecretEnvelopeCodec.java \
  core/key-material/src/test/java/pro/deta/orion/keymaterial/ConfigurationSecretEnvelopeCodecTest.java
git commit -m "Define versioned configuration secret envelopes"
```

### Task 3: Replace direct AES-GCM values with envelope encryption

**Files:**
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationCipherCapability.java`
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialCapabilities.java`
- Delete: `core/key-material/src/main/java/pro/deta/orion/keymaterial/EncryptedConfigurationValue.java`
- Modify: `core/key-material/src/test/java/pro/deta/orion/keymaterial/KeyMaterialCapabilitiesTest.java`

**Step 1: Replace the existing happy-path test with a failing envelope round-trip test**

Change the public capability contract expected by the test to:

```java
ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context)
        throws GeneralSecurityException;

byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context)
        throws GeneralSecurityException;
```

Persist and reopen the in-memory PKCS12 store between sealing and opening. Assert the plaintext round trip,
envelope alias/version/algorithm metadata, and absence of any `SecretKey`
return type from the public capability.

**Step 2: Run the happy-path test and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/key-material -am \
  -Dtest=KeyMaterialCapabilitiesTest#sealsConfigurationEnvelopeAcrossMaterialReload \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `seal` and `open` are not implemented.

**Step 3: Implement minimal envelope sealing and opening**

In `KeyMaterialCapabilities`, use these fixed constants:

```java
private static final String AES_WRAP = "AESWrap";
private static final String AES_GCM = "AES/GCM/NoPadding";
private static final String BASE64_URL = "base64url";
private static final int DATA_KEY_BITS = 256;
private static final int GCM_TAG_BITS = 128;
private static final int GCM_NONCE_BYTES = 12;
```

For `seal`:

1. Generate a fresh AES-256 `SecretKey` with the capability's `SecureRandom`.
2. Wrap it with `Cipher.getInstance("AESWrap")` initialized from `owner.secretKey(alias)`.
3. Initialize AES-GCM with a fresh 12-byte nonce.
4. Call `updateAAD(context.authenticatedBytes())` before `doFinal(plaintext)`.
5. Return an envelope containing the registered alias and material version.

For `open`, require exact envelope version, alias, material version,
algorithms, and encoding; unwrap the data key; initialize GCM with the envelope
nonce; apply the expected context as AAD; then decrypt. Map authentication-tag
failure to `ConfigurationSecretException.Reason.AUTHENTICATION_FAILED` without
including secret values.

**Step 4: Run the happy-path test and verify GREEN**

Run the command from Step 2. Expected: PASS.

**Step 5: Add one failing freshness test**

Seal identical bytes twice under the same context. Assert different wrapped keys, nonces, and ciphertexts, then
open both successfully.

Run the focused method and confirm it fails before any adjustment, then make the smallest implementation change
needed and confirm it passes.

**Step 6: Add failing authenticated-context and tampering tests**

Use parameterized or ordinary loops to change each context component separately and assert
`AUTHENTICATION_FAILED`. Tamper independently with wrapped key, nonce, and ciphertext and assert a safe failure.
Construct envelopes with the wrong alias and material version and assert `MATERIAL_MISMATCH` before decryption.

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/key-material -am \
  -Dtest=KeyMaterialCapabilitiesTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected before implementation adjustments: at least one new assertion fails for the intended missing behavior.
After the minimal adjustments: PASS.

**Step 7: Remove the obsolete direct-value type**

Delete `EncryptedConfigurationValue` only after all production and test callers
use the full envelope. Do not add a test whose sole purpose is proving the old
type or old methods are absent.

**Step 8: Run all key-material tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/key-material -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 9: Commit**

```bash
git add core/key-material/src/main/java/pro/deta/orion/keymaterial/ConfigurationCipherCapability.java \
  core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialCapabilities.java \
  core/key-material/src/main/java/pro/deta/orion/keymaterial/EncryptedConfigurationValue.java \
  core/key-material/src/test/java/pro/deta/orion/keymaterial/KeyMaterialCapabilitiesTest.java
git commit -m "Encrypt configuration secrets with context-bound envelopes"
```

### Task 4: Verify the configuration cryptography slice

**Files:**
- Modify if necessary: files changed in Tasks 1-3 only

**Step 1: Check formatting and forbidden leakage**

Run:

```bash
git diff --check main...HEAD
awk 'length($0)>112 {print FNR ":" length($0) ":" $0}' \
  core/key-material/src/main/java/pro/deta/orion/keymaterial/*.java \
  core/key-material/src/test/java/pro/deta/orion/keymaterial/*.java
rg -n "plaintext|ciphertext|wrappedDataKey|secretKey" \
  core/key-material/src/main/java/pro/deta/orion/keymaterial
```

Inspect every message and log path found by the last command. Expected: no error text or logging includes secret
values or encoded secret material.

**Step 2: Run module verification outside the sandbox**

Run:

```bash
mvn verify -Pdev -T 4 -q -pl core/key-material -am
```

Expected: PASS.

**Step 3: Review task diff and commit any verification fix**

Run:

```bash
git status --short
git diff main...HEAD
```

If a fix is required, follow a fresh RED/GREEN cycle and commit it with a concise single-line subject. Do not
touch the unrelated baseline failure in `session-host`.

**Step 4: Prepare branch completion**

Invoke `superpowers:requesting-code-review`, address blocking findings, rerun verification, then invoke
`superpowers:finishing-a-development-branch`. Follow the repository rule to squash unique task commits, delete
the completed leaf task directory and its parent link in the squashed commit, cherry-pick to `main`, run the
required post-commit `make test`, and remove the worktree and task branch before reporting completion.
