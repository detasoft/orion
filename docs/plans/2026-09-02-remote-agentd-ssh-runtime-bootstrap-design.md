# Remote AgentD SSH Runtime Bootstrap Design

## Goal

Give Orion Server one short, authenticated SSH transaction that selects and
installs a compatible AgentD runtime bundle, verifies its contents, starts
AgentD detached from SSH with a single-use permit supplied through channel
input, and then closes SSH. The bundle contains both AgentD and the native
`session-host` executable that AgentD will use.

This is the first leaf of the broader remote-machine provisioning task. It
does not enroll keys with passwords, replace a currently running AgentD,
persist server-side machine records, or add administration UI.

## Task Decomposition

The aggregate is split into four leaves:

1. `ssh-runtime-bootstrap` implements verified SSH, platform selection,
   versioned bundle upload, integrity verification, atomic activation, and
   detached launch.
2. `ssh-key-enrollment` implements the one-attempt bootstrap password and the
   idempotent `ssh-copy-id` equivalent.
3. `agent-replacement-and-recovery` identifies and terminates an old AgentD,
   applies recovery timeouts, and retries without affecting session hosts.
4. `administration-and-end-to-end-acceptance` wires material references,
   encrypted configuration, durable machine records, UI, and the real packaged
   AgentD/session-host acceptance path.

The first leaf keeps command routing outside its scope, but establishes the
real AgentD launch contract: `AgentConfiguration` requires the absolute
`--session-host` executable path and carries it through AgentD assembly for the
session-runtime layer. A contract test feeds the provisioner's generated
argument vector through the real AgentD parser. The live SSH acceptance fixture
proves the process boundary: an uploaded AgentD fixture survives SSH closure
and invokes the uploaded `session-host` fixture. The final leaf repeats that
acceptance with the packaged runtime and central control path.

## Selected Approach

Create a small top-level `agent-provisioning` Maven module using the Apache
MINA sshd client already versioned by Orion. Keep the public provisioning API
independent of MINA types: callers supply a validated endpoint, an exact host
public key, a purpose-resolved client key pair, a catalog of runtime bundles,
and a launch request.

The alternatives are less suitable:

- Shelling out to the operating-system `ssh` command makes host-key isolation,
  secret channel input, portable error classification, and cancellation depend
  on mutable user configuration and external binaries.
- Adding JSch introduces a second SSH implementation without improving the
  existing dependency fit or test infrastructure.

## Contracts

`SshEndpoint` contains host, port, remote account, and the exact expected host
public key. Authentication accepts an already-resolved `KeyPair`; resolution
from the protected key-material store or an encrypted `orion.xml` value remains
outside this leaf. This keeps plaintext private-key and passphrase material out
of provisioning records, command lines, exceptions, and logs.

`RemotePlatform` is the normalized result of a remote `uname -s` and
`uname -m` probe. The initial implementation recognizes Linux and macOS on
`x86_64` and `aarch64`/`arm64`; unsupported combinations fail before upload.

`RemoteRuntimeBundle` contains a safe version identifier and exactly two local
artifacts named `agentd` and `session-host`. It records the expected SHA-256 of
each artifact. `RuntimeBundleCatalog` selects exactly one bundle for the probed
platform and requested AgentD version. It may retain multiple versions for the
same platform so operators can stage upgrades and rollbacks.

`AgentdLaunchRequest` contains non-secret server URI, state directory, stable
agent ID, generation, launch ID, frame bound, and reported version. A separate
closeable `LaunchPermit` owns the permit bytes, returns defensive copies, and
zeroes its storage on close. No `toString` includes permit data.

The launch argument vector includes the selected immutable release's
`session-host` executable. AgentD accepts it as the required `--session-host`
configuration path; dispatching commands to that executable belongs to the
separate command-orchestration task.

## SSH Transaction

The provisioner performs one operation per SSH session:

1. Connect with a native connect deadline, verify the presented host key by
   exact public-key comparison, disable user SSH configuration and default
   identities, enable only public-key authentication, add only the selected
   client identity, and authenticate with a native authentication deadline.
2. Probe `uname` and select the exact matching platform and requested version
   before creating remote paths.
3. Create a private staging directory below
   `<installRoot>/releases/.staging-<launchId>` with mode `0700`.
4. Upload each artifact through a non-PTY exec channel whose standard input is
   redirected to a remote file. Remote paths and arguments are single-quoted by
   one strict POSIX quoting utility; version and identifier fields also have
   narrow character validation.
5. Run the platform-appropriate SHA-256 command remotely, compare its canonical
   lowercase digest to the server-recorded value, and fail without activation
   on any mismatch.
6. Mark both artifacts executable, replace any incomplete directory for the
   same version, and rename staging to the verified version directory. A retry
   after connection loss can reuse an already matching release or replace only
   its own incomplete staging directory.
7. Execute a small POSIX launch command. It reads one newline-terminated permit
   from SSH channel input, pipes it to AgentD stdin, creates a new session with
   `setsid`, redirects AgentD stdout and stderr to its versioned log file, and
   disconnects every inherited SSH stream. The permit never appears in the
   command, environment, remote file, output, or log.
8. Require a launch acknowledgement from the bootstrap shell. Only then create
   a temporary `current` symlink and atomically rename it into place. If launch
   setup or acknowledgement fails, preserve the prior `current` target and keep
   the newly verified release for safe retry. Close the exec channel and SSH
   session immediately after the switch. Agent availability is determined
   later from its outbound control connection, never from SSH.

The first leaf never terminates a prior AgentD. Callers must not use it as a
replacement operation; the replacement-and-recovery leaf will require verified
termination before invoking this transaction.

## Timeouts and Cancellation

MINA connect, authentication, channel-open, and channel-wait futures receive
their relevant configured deadlines. A single shared scheduled watchdog bounds
the whole bootstrap operation and requests an immediate, non-blocking close of
the underlying SSH client when the deadline expires. The blocking provisioning call runs on its caller; it
does not allocate a platform or virtual thread per read or write. Closing the
session unblocks an upload or response read that lacks a finer transport-native
deadline.

Native connect, authentication, and command deadline exceptions are classified
as typed timeout failures. This satisfies the repository timeout rule: thread
allocation is never used as a timeout mechanism, and every timeout either
belongs to the SSH transport or closes that transport from a shared watchdog.

## Errors and Secret Handling

Failures are classified as connection, host identity, authentication, remote
platform, transfer, integrity, activation, launch, or timeout failures. Public
messages contain the endpoint and failed phase where useful but never key
encoding, permit bytes, command input, or captured unrestricted remote output.
Remote stderr is bounded before it is retained in an exception.

The provisioner closes channels and sessions in every failure path. It clears
temporary permit copies after writing and asks the permit owner to close it at
the orchestration boundary. Failed staging content is non-secret and may remain
for diagnosis, but retries remove only the staging directory for the same
launch ID.

## Verification

Unit tests cover validation, platform normalization and bundle selection,
strict quoting, digest comparison, bounded output, permit zeroing, and error
classification.

Live tests use an in-process Apache MINA SSH server bound to loopback with fixed
host and client keys. Its command implementation writes into a temporary remote
root while exercising the real client connection, host-key verification,
public-key authentication, channel input, and session closure. Tests cover:

- successful upload, digest verification, atomic activation, and detached
  launch;
- exact host-key mismatch and authentication failure before any remote write;
- isolation from default identities and user SSH configuration;
- digest mismatch leaving `current` unchanged;
- retry after a partially uploaded staging directory;
- launch setup failure retaining the verified release while leaving `current`
  unchanged;
- configured timeout closing the SSH session; and
- an AgentD fixture that invokes the uploaded session-host fixture after the
  client SSH session has closed.

The test fixture validates this leaf's executable-bundle boundary. Real jlink
packaging, AgentD registration, and a session request through the central
control path remain explicit acceptance criteria of the fourth leaf rather
than being simulated here.
