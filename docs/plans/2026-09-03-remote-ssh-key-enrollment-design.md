# Remote SSH Key Enrollment Design

## Goal and Boundary

Enroll one selected Orion SSH client public key on an exact remote account without
weakening host verification, consulting user SSH configuration, or retaining a
bootstrap password. Enrollment must be idempotent, preserve unrelated
`authorized_keys` content and existing permissions, and finish only after a new
public-key-only session authenticates successfully.

This leaf adds the key-material purpose and provisioning primitive. It does not add
`orion.xml` schema, reference resolution, runtime wiring, administration UI, or
end-to-end machine provisioning. Those belong to
`administration-and-end-to-end-acceptance`.

## Selected Approach

Add an Apache MINA based `SshKeyEnroller` to `agent-provisioning` and extend the
existing `MinaSshOperation` connection boundary with explicit public-key-only and
password-only modes. Both modes retain the current exact-host-key verifier, empty
host configuration resolver, empty default identity provider, native connect and
authentication deadlines, and shared operation watchdog.

The alternatives remain less suitable:

- Shelling out to `ssh-copy-id` or `ssh` would make identity isolation, host-key
  pinning, password handling, error classification, and cancellation depend on
  external programs and mutable user configuration.
- Building an independent MINA client path would duplicate the security-sensitive
  connection and timeout rules already centralized in `MinaSshOperation`.
- Adding only an untyped `KeyPair` argument would bypass the protected-material
  purpose and descriptor checks required at this boundary.

## Protected Client-Key Material

`KeyMaterialPurpose` gains `SSH_CLIENT` with storage name `ssh-client`.
`KeyMaterialCapabilities.sshClientKey(descriptor)` requires the exact registered
descriptor, the `SSH_CLIENT` purpose, and asymmetric material. It returns an
`SshClientKeyCapability` whose
only material operation yields a defensive `KeyPair` for the selected descriptor.
The material service continues to validate persisted purpose, scope, version, and
algorithm metadata before a capability can be opened.

The enroller accepts this capability, not an alias or arbitrary private key. The
later administration leaf will choose and construct the descriptor from protected
configuration.

## Bootstrap Password Ownership

`BootstrapPassword` is an `AutoCloseable` value with an explicitly wipeable direct
`ByteBuffer`. Factory methods copy UTF-8 password data from a caller-owned `char[]`
or `byte[]` and clear that caller array immediately, including exceptional paths.
The direct storage is never exposed and is overwritten on consumption, every
enrollment outcome, and explicit close. Closing is idempotent.

The direct buffer is the only retained password representation. Apache MINA
2.13.2's `PasswordIdentityProvider` requires a `String`, so the password-only open
path decodes and creates one transient `String` immediately before the single
authentication attempt. Java `String` storage cannot be explicitly zeroed. The
temporary mutable decode arrays are wiped, the provider is removed, and the
password session is closed as soon as enrollment finishes. The transient string is
never placed in commands, environment, output, logs, exceptions, files, or
`toString` values.

The enroller takes ownership of an optional `BootstrapPassword`. A successful
initial key authentication closes it without materializing a string. Every return
or exception closes it.

## Enrollment Data Flow

1. Resolve the selected `KeyPair` through the SSH client-key capability and format
   its public key with Apache MINA's canonical OpenSSH formatter.
2. Open a fresh public-key-only operation using only that selected identity. If
   authentication succeeds, close the operation and return success without
   requiring or materializing a password.
3. If and only if that attempt fails with typed `AUTHENTICATION` and a bootstrap
   password is present, open exactly one fresh password-only operation. Connection,
   host-key, and timeout failures are returned immediately with no fallback.
4. Execute one fixed, narrowly validated POSIX enrollment program. Supply the
   canonical non-secret public-key line through channel stdin. The command never
   contains the key or password.
5. Close the password-authenticated operation completely.
6. Open a third, fresh public-key-only operation using only the selected identity.
   Report success only if this verification authenticates. A verification failure
   leaves an appended public key in place for diagnosis and safe retry.
7. Close and wipe the bootstrap password in an outer `finally` block.

## Remote POSIX Mutation

The enrollment program has no interpolated remote values. It reads exactly one
canonical key line from stdin, validates its algorithm and base64 blob, and rejects
additional input. It rejects symbolic links and unexpected object types at
`$HOME/.ssh` and `$HOME/.ssh/authorized_keys` before mutation.

When absent, it creates `.ssh` with mode `0700` and `authorized_keys` with mode
`0600` under an owner-only umask. When either already exists, it does not chmod or
replace it. It scans key records for the exact public-key blob, including records
with authorized-key options, and appends the canonical line only when that blob is
absent. It never truncates or rewrites existing content, so comments, unrelated
keys, ordering, and permissions remain unchanged. A trailing newline is inserted
only when needed before an append. Repeating enrollment cannot duplicate the key.

The command uses distinct exit statuses for malformed input, unsafe `.ssh` state,
unsafe `authorized_keys` state, and write or permission failures. `SshKeyEnroller`
maps these to typed `EnrollmentFailure` values with actionable phase messages and
without including unrestricted remote output.

## Errors and Security Properties

Enrollment errors distinguish connection, host identity, authentication, missing
bootstrap password, unsafe remote state, remote write, verification, and timeout.
Initial non-authentication SSH failures retain the existing provisioning failure
classification and never trigger password fallback. A wrong password causes one
password authentication attempt, no command, no file mutation, and direct-buffer
clearing.

All sessions and channels close on every path. Password values are absent from
commands, captured output, exception messages and cause text produced by Orion,
logs, `toString`, and remote files. Public keys are intentionally non-secret but
still travel through stdin rather than command text.

Timeouts use MINA's connect, authentication, channel-open, and channel-wait
deadlines plus the existing shared scheduled watchdog that closes the SSH client.
No platform or virtual thread is created per blocking I/O call.

## Verification

Key-material tests generate, save, reload, and select RSA and Ed25519 `SSH_CLIENT`
material, then reject wrong purpose, scope, descriptor metadata, and unsupported
algorithm use.

Password tests prove the retained buffer is direct, caller inputs are cleared,
mutable temporary data is overwritten, close is idempotent, and direct storage is
cleared after public-key success, password success, authentication failure, remote
failure, and explicit close. A test-only inspection hook may expose only whether
the direct storage has been cleared, and must carry Orion's `@TestOnly` marker.

Live loopback tests extend the in-process MINA server to record authentication
methods, session identities, commands, stdin, and filesystem effects. They cover:

- password enrollment in one session followed by verification in a different
  public-key-only session;
- repeat idempotency and preservation of unrelated keys, comments, ordering, and
  existing POSIX permissions;
- an already-enrolled key succeeding without password authentication or password
  materialization;
- one wrong-password attempt, no mutation, and password clearing;
- exact host-key mismatch without fallback;
- unsafe symbolic-link or malformed remote state and write failure;
- verification failure after a harmless append; and
- absence of the password from commands, files, logs, results, and errors.

Focused RED/GREEN runs use `make run-test` for `key-material` and
`agent-provisioning`. Final development verification uses `mvn verify -Pdev -T 4`,
and every non-documentation implementation commit is followed by `make test`.
