# Landlock Capability Fallback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Start requested build sessions with a warning when Landlock ABI 9 is unavailable, while keeping
invalid policies and rule-application failures fatal.

**Architecture:** `session-host` remains the capability authority and always converts only its existing
compatibility-stage error into an unenforced launch. AgentD still compiles and validates the source policy but no
longer selects an unavailable-policy mode. Metadata v1 keeps its existing field with the fixed compatibility value
`run-unsandboxed`.

**Tech Stack:** Rust 1.97, `landlock` 0.4.7, Java 25, JUnit 5, AssertJ, Maven, GNU Make.

---

### Task 1: Specify the native CLI and capability fallback

**Files:**

- Modify: `session-host/src/cli.rs`
- Modify: `session-host/tests/unix_process_host.rs`

**Step 1: Write the failing CLI tests**

In `session-host/src/cli.rs`, remove `--sandbox-unavailable` from the successful complete-command fixture and add a
case to `rejects_duplicate_and_invalid_options`:

```rust
let removed = parse(session_arguments(&["--sandbox-unavailable", "fail"]));
assert_eq!(
    removed.unwrap_err().to_string(),
    "unknown option: --sandbox-unavailable"
);
```

Remove assertions that expect `SessionOptions.sandbox_unavailable` or a default mode. The new test must initially
fail because the option is still accepted.

**Step 2: Run the Rust suite to verify RED**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: the removed-option assertion fails because parsing still succeeds.

**Step 3: Write the failing process-host test**

Replace the explicit-mode tests with one unsupported-machine expectation. On non-Linux, and in the existing Linux
`current_landlock_abi() < 9` branch, launch with only `--sandbox-policy` and assert:

```rust
assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
assert!(
    String::from_utf8_lossy(&output.stderr)
        .contains("warning: Landlock ABI 9 is unavailable; running without filesystem restrictions")
);
let metadata = journal::read_metadata(directory).unwrap();
assert_eq!(metadata.sandbox.enforcement, journal::SandboxEnforcement::None);
assert_eq!(
    metadata.sandbox.unavailable_policy,
    journal::SandboxUnavailablePolicy::RunUnsandboxed
);
```

Rename `invalid_grants_are_fatal_even_with_unsandboxed_fallback` to
`invalid_grants_remain_fatal_when_landlock_is_unavailable` and remove the obsolete CLI argument. The invalid grant
assertions must stay fatal on every Unix platform.

**Step 4: Run the Rust suite to verify the capability RED**

Run outside the sandbox:

```bash
make session-host-test
```

Expected on macOS or Linux with Landlock ABI below 9: the unsupported-machine test receives exit code 70 instead of
success. On newer Linux, run the classification unit test and use the old-kernel guest for the real RED observation.

Do not remove `SessionOptions.sandbox_unavailable` yet. Both native RED tests must exist before the coupled
production change because `platform/sandbox.rs` and `platform/unix.rs` still read that field.

### Task 2: Remove the selector and implement capability fallback

**Files:**

- Modify: `session-host/src/cli.rs`
- Modify: `session-host/src/main.rs`
- Modify: `session-host/src/platform/sandbox.rs`
- Modify: `session-host/src/platform/unix.rs`
- Modify: `session-host/tests/unix_process_host.rs`
- Modify: `session-host/src/journal.rs`
- Modify: `session-host/protocol/fixtures/metadata-v1.json`
- Modify: `session-host/protocol/README.md`
- Modify: `session-host/MODULE_REVIEW.md`

**Step 1: Remove the CLI mode**

Delete `SandboxUnavailable`, `SessionOptions.sandbox_unavailable`, the parser accumulator and match arm,
`parse_sandbox_unavailable`, and the help line. A policy needs only:

```text
--sandbox-policy PATH        Filesystem sandbox policy
```

Do not add a replacement flag. Perform this removal in the same production step as the fallback and metadata
changes below so the Rust tree never claims a GREEN state while dependent code still reads the removed field.

**Step 2: Implement the capability-only fallback**

In `PreparedSandbox::prepare`, remove the option check but keep the existing classification boundary:

```rust
Err(error) if error.unavailable => {
    eprintln!(
        "session-host: warning: Landlock ABI 9 is unavailable; \
         running without filesystem restrictions: {}",
        error.detail
    );
    Ok(Self {
        policy: Some(policy),
        ruleset: None,
    })
}
```

On non-Linux, validate all policy grant paths first, print the same warning with platform detail, and return an
unenforced prepared sandbox. Do not change `unavailable_at`: only `PrepareStage::Compatibility` may return true.
`create`, `add_rule`, grant-path validation, and child `restrict_self` errors remain unchanged and fatal.

In `initial_metadata`, always write:

```rust
unavailable_policy: SandboxUnavailablePolicy::RunUnsandboxed,
```

Update the metadata round-trip fixture and `metadata-v1.json` to that fixed value. Update the protocol README and
module review to describe automatic capability fallback and fail-closed rule application.

**Step 3: Run the Rust suite to verify GREEN**

Run outside the sandbox:

```bash
make session-host-test
git diff --check
```

Expected: fallback, invalid-grant, metadata, and CLI tests pass; formatting check is clean.

**Step 4: Verify the real old-kernel path**

Copy the task source to `root@gw.ntechs.ru:30022`, build with the existing hermetic Rust and Zig toolchains, and run
the exact unsupported-Landlock process test on kernel 5.4:

```bash
uname -r
make session-host-build
```

Then invoke the built host with a valid compiled policy and `/usr/bin/true`. Expected: kernel `5.4.72-std-def-alt1`,
exit code 0, one warning in stderr, and metadata with `requested: true`, `enforcement: none`, and
`unavailablePolicy: run-unsandboxed`.

**Step 5: Commit the coupled native change**

```bash
git add session-host
git commit -m "Fall back when Landlock is unavailable"
```

### Task 3: Remove the mode from AgentD launch specifications

**Files:**

- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionSpec.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/NativeRuntime.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`

**Step 1: Write the failing AgentD assertion**

Keep the existing two-argument sandbox fixtures and extend
`compilesSandboxPolicyIntoSessionAndPassesOnlyGeneratedPath`:

```java
assertThat(launcher.command).doesNotContain("--sandbox-unavailable");
```

Run this before modifying `SessionSpec` or `NativeRuntime`; it must compile and then fail because the current command
contains the option. Changing fixture constructors before the RED run would test a Java compilation error rather
than the obsolete command behavior.

**Step 2: Run the focused Java test to verify RED**

Run outside the sandbox:

```bash
make run-test MODULE=agentd TEST='NativeRuntimeTest'
```

Expected: `compilesSandboxPolicyIntoSessionAndPassesOnlyGeneratedPath` fails on the obsolete option.

**Step 3: Simplify the AgentD contract**

Change the sandbox record to:

```java
public record Sandbox(Optional<Path> policy) {
    public Sandbox {
        policy = Objects.requireNonNull(policy, "policy");
    }

    public static Sandbox none() {
        return new Sandbox(Optional.empty());
    }
}
```

Delete `SessionSpec.Unavailable`. In `NativeRuntime.command`, keep `--sandbox-policy` but remove the entire block that
adds `--sandbox-unavailable`. In the same production step, simplify every `NativeRuntimeTest` sandbox fixture to
construct `new SessionSpec.Sandbox(Optional.of(policy))` so the source tree remains compilable.

**Step 4: Run the focused Java test to verify GREEN**

Run outside the sandbox:

```bash
make run-test MODULE=agentd TEST='NativeRuntimeTest'
```

Expected: all `NativeRuntimeTest` cases pass.

**Step 5: Commit the AgentD change**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/runtime \
  agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java
git commit -m "Remove AgentD Landlock fallback selection"
```

### Task 4: Verify, squash, transfer, and close the task

**Files:**

- Modify: `docs/plans/current-work/native-session-host/TASK.md`
- Delete: `docs/plans/current-work/native-session-host/landlock-capability-fallback/TASK.md`

**Step 1: Run component verification**

Run outside the sandbox:

```bash
make session-host-test
make run-test MODULE=agentd TEST='NativeRuntimeTest,JsonSessionManifestReaderTest'
mvn verify -Pdev -T 4
git diff --check
```

Expected: all relevant tests pass. If the two independently diagnosed inherited-SIGINT process tests still fail on
the old Linux guest, report them as pre-existing and keep their fix outside this task.

**Step 2: Review the task diff**

Confirm the diff contains only the capability fallback, removed selectors, fixed metadata compatibility value,
tests, and documentation. Verify no policy parse, expansion, path validation, rule construction, or child
restriction failure was changed into a fallback.

**Step 3: Squash the task worktree**

Squash task-only commits into one commit with this subject:

```text
Fall back when Landlock is unavailable [task: native-session-host/landlock-capability-fallback]
```

In that same commit, remove the completed leaf task directory and its link from the parent task node. Keep the
ordinary design and implementation plan documents.

**Step 4: Transfer to main and run post-commit tests**

Cherry-pick the squashed commit to `main`, then run outside the sandbox:

```bash
make test
```

Expected: the regular project test suite passes. If it fails only because of unrelated working-tree changes, report
that failure without modifying them.

**Step 5: Remove the completed worktree and branch**

Remove the worktree and delete its task branch only after the cherry-pick and post-commit test are confirmed. Verify
that `git worktree list` no longer contains the completed worktree before reporting completion.
