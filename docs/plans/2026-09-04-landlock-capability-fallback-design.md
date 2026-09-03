# Landlock Capability Fallback Design

## Goal

Start a requested build session when the machine cannot provide Landlock ABI
9, with an explicit warning that the child is running without filesystem
restrictions. Invalid policies and failures while constructing or applying a
ruleset remain fatal.

## Decision

The native session host is the authority for Landlock capability. Remove the
configurable `fail` versus `run-unsandboxed` choice from its CLI and from
AgentD's `SessionSpec`. A caller cannot turn machine incompatibility into a
launch failure or accidentally produce behavior different from other callers.

AgentD continues to parse the user policy, resolve ordered allow and deny
rules, and write the canonical CBOR policy. The session host continues to
validate that compiled policy and its grant paths before considering a
capability fallback.

## Launch Flow

When a policy is requested, the session host performs these steps:

1. Decode and validate the compiled policy.
2. Open and validate every grant path without following symbolic links.
3. Require Landlock ABI 9 through the compatibility check.
4. If the compatibility check reports that Landlock ABI 9 is unavailable,
   print a warning to stderr and continue without a ruleset.
5. If compatibility succeeds, create the ruleset, add every rule, and apply it
   to the PTY child before `exec`.

Only step 4 is non-fatal. Decode errors, invalid or inaccessible grant paths,
ruleset creation failures, rule-add failures, incomplete enforcement, and
child restriction failures stop the launch. This preserves the distinction
between a machine that lacks the sandbox capability and a requested policy
that could not be applied correctly.

The warning uses a stable prefix suitable for the existing session-host log:

```text
session-host: warning: Landlock ABI 9 is unavailable; running without filesystem restrictions: DETAIL
```

The detail comes from compatibility negotiation and contains no command,
environment, or policy contents.

## Interfaces and Metadata

Remove `--sandbox-unavailable` from the CLI, help, and AgentD command builder.
Remove the corresponding enum and field from Rust session options and Java
session specifications.

Metadata continues to report `requested: true` and `enforcement: none` after a
capability fallback. Metadata v1 retains `unavailablePolicy` with the fixed
value `run-unsandboxed` so existing readers and fixtures remain compatible.
The field is compatibility data rather than a runtime choice.

## Testing

Rust unit tests lock the capability-error classification and the fixed
metadata value. Process-host tests verify that an unsupported Landlock machine
starts the child and emits the warning. Existing and extended tests verify
that malformed policies, invalid grant paths, ruleset construction failures,
and child restriction failures remain fatal after compatibility succeeds.

AgentD tests verify that a sandboxed launch passes only the compiled policy
path and no unavailable-policy option. CLI tests reject the removed option and
show that no mode is required for a requested policy.

Run focused Java tests locally and compile the Rust host for Linux. Exercise
the real unsupported-kernel path on the available Linux 5.4 Xen guest, then
run the broader project verification required by the repository workflow.
