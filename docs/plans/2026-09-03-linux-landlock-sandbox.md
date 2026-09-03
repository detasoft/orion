# Linux Landlock Sandbox Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Compile an ordered user policy DSL in AgentD and enforce its positive
CBOR form on the Linux PTY child tree with Landlock.

**Architecture:** AgentD parses source policy files, resolves deeper-path and
last-rule precedence, and expands denied descendants against a filesystem
snapshot. It writes a canonical, positive-only CBOR policy into the session
directory. The Rust host validates that handoff, constructs an ABI 9 Landlock
ruleset while the host remains unrestricted, and applies it with
`no_new_privs` only in the child before `exec`.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Java NIO, canonical CBOR, Rust 1.97,
`landlock` 0.4.7, libc, Maven, and Cargo.

Design: [Linux Landlock sandbox design](2026-09-03-linux-landlock-sandbox-design.md)

---

### Task 1: Define and parse the AgentD policy language

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/LandlockRight.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/SourcePolicy.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/PolicyException.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/SourcePolicyParser.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/package-info.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/sandbox/SourcePolicyParserTest.java`

**Step 1: Write the failing parser tests**

Cover the version header, blank lines, comments, `none`, each preset, a mixed
bracketed list, quoted `\\` and `\"`, and replacement of a duplicate path by
the last rule. Add parameterized invalid cases for a missing header, unknown
right, `none` in a list, relative or non-normal path, bad escape, trailing
tokens, too many rules, and oversized input.

The central assertion should establish the fixed ABI 9 masks:

```java
assertThat(parser.parse("""
        landlock 1
        ro "/"
        none "/home/user/.ssh"
        [rw, read-dir, make-reg] "/workspace"
        """).rules()).containsExactly(
        new SourcePolicy.Rule(Path.of("/"), LandlockRight.READ_FILE.mask(), 2),
        new SourcePolicy.Rule(Path.of("/home/user/.ssh"), 0, 3),
        new SourcePolicy.Rule(
                Path.of("/workspace"),
                LandlockRight.READ_FILE.mask()
                        | LandlockRight.WRITE_FILE.mask()
                        | LandlockRight.TRUNCATE.mask()
                        | LandlockRight.READ_DIR.mask()
                        | LandlockRight.MAKE_REG.mask(),
                4));
```

**Step 2: Run the focused test and verify it fails**

Run outside the sandbox:

```bash
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.sandbox.SourcePolicyParserTest'
```

Expected: FAIL because the sandbox policy classes do not exist.

**Step 3: Implement the policy model and scanner**

Give every right its DSL token, UAPI mask, and whether it requires an ancestor
directory grant:

```java
enum LandlockRight {
    EXECUTE("execute", 1L << 0, false),
    WRITE_FILE("write-file", 1L << 1, false),
    READ_FILE("read-file", 1L << 2, false),
    READ_DIR("read-dir", 1L << 3, true),
    // Bits 4 through 15 follow the same UAPI order.
    RESOLVE_UNIX("resolve-unix", 1L << 16, false);

    static final long HANDLED_MASK = (1L << 17) - 1;
}
```

Use a small character scanner rather than regular-expression splitting so
comments and quoted paths have one unambiguous implementation. Deduplicate by
normalized `Path` after parsing, retaining the last rule and its source line.
Keep parsing independent from the filesystem; symbolic-link and existence
checks belong to snapshot compilation.

**Step 4: Run the focused test and verify it passes**

Run the command from Step 2. Expected: PASS.

**Step 5: Commit**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/sandbox \
  agentd/src/test/java/pro/deta/orion/agentd/sandbox/SourcePolicyParserTest.java
git commit -m "Parse AgentD Landlock policy DSL"
```

### Task 2: Compile precedence and deny regions against a snapshot

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/CompiledPolicy.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/LandlockPolicyCompiler.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/sandbox/LandlockPolicyCompilerTest.java`

**Step 1: Write failing compiler tests**

Build real trees under `@TempDir`. Cover:

- `ro root` plus `none root/home` emits current siblings but neither the root
  nor `home`;
- a deeper deny emits siblings at every path component;
- a deeper allow under implicit `none` emits the allow directly;
- an added right below an ancestor keeps the ancestor grant and adds only the
  new bit below it;
- the last rule at one normalized path wins, while a deeper rule wins even if
  an ancestor appears later;
- missing denies stop at the first missing component and missing allows fail;
- new siblings created after compilation are absent from the result;
- symlink entries are not traversed or emitted;
- `read-dir`, `make-*`, `remove-*`, or `refer` with a restrictive descendant
  fails as not exactly representable;
- deterministic bytewise path ordering and the emitted-grant limit.

Use the agreed example as an explicit acceptance test:

```java
CompiledPolicy policy = compiler.compile(parser.parse("""
        landlock 1
        ro "%s"
        none "%s"
        """.formatted(root, home)));

assertThat(policy.rules()).extracting(CompiledPolicy.Rule::path)
        .containsExactly(root.resolve("bin"), root.resolve("usr"));
```

**Step 2: Run the focused test and verify it fails**

```bash
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.sandbox.LandlockPolicyCompilerTest'
```

Expected: FAIL because `LandlockPolicyCompiler` is missing.

**Step 3: Implement a per-right policy trie compiler**

Build a component trie from the deduplicated source rules. Walk it once for
each `LandlockRight`, carrying the inherited boolean decision:

```text
allow with no descendant removal -> emit this path
allow with descendant removal:
    ancestor-directory right      -> exactness error
    object right                  -> list current children; emit unaffected
                                     children and recurse into override branches
deny with descendant allowance    -> recurse only into policy branches
deny with no descendant allowance -> emit nothing
```

Use `DirectoryStream` with `NOFOLLOW_LINKS` attributes. Do not descend into
siblings; a sibling path-beneath grant covers that existing subtree. Merge
rights by path in a `TreeMap` ordered by raw UTF-8 bytes, remove zero masks, and
return immutable values. Include source line and boundary path in every error.

**Step 4: Run all AgentD sandbox tests**

```bash
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.sandbox.*Test'
```

Expected: PASS.

**Step 5: Commit**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/sandbox \
  agentd/src/test/java/pro/deta/orion/agentd/sandbox/LandlockPolicyCompilerTest.java
git commit -m "Compile AgentD deny policies to Landlock grants"
```

### Task 3: Freeze the canonical CBOR handoff

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/sandbox/CompiledPolicyWriter.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/sandbox/CompiledPolicyWriterTest.java`
- Create: `session-host/protocol/fixtures/sandbox-policy-v1.hex`
- Modify: `session-host/protocol/README.md`

**Step 1: Write the failing canonical encoding test**

Create a policy with deliberately unsorted input and assert exact bytes for:

```text
[1, 131071, [["/bin", 5], ["/usr", 5], ["/workspace", 20926]]]
```

The test must read `session-host/protocol/fixtures/sandbox-policy-v1.hex` and
compare it to the writer output, establishing one cross-language fixture.

**Step 2: Run the writer test and verify it fails**

```bash
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.sandbox.CompiledPolicyWriterTest'
```

Expected: FAIL because the writer and fixture are missing.

**Step 3: Implement the minimal canonical CBOR writer**

Support only the contract's definite arrays, unsigned integers, and text. Do
not expose or duplicate the general Agent protocol codec. Encode shortest-form
integer and length arguments, validate UTF-8 path length before allocation, and
write the complete byte array atomically to `sandbox-policy.cbor` with owner
read/write permissions.

**Step 4: Document the schema and verify the fixture**

Add the fixed array layout, ABI 9 bit table, canonical ordering, bounds, and
rejection rules to `session-host/protocol/README.md`. Run the Step 2 command;
expected: PASS.

**Step 5: Commit**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/sandbox/CompiledPolicyWriter.java \
  agentd/src/test/java/pro/deta/orion/agentd/sandbox/CompiledPolicyWriterTest.java \
  session-host/protocol/fixtures/sandbox-policy-v1.hex session-host/protocol/README.md
git commit -m "Define compiled Landlock CBOR policy"
```

### Task 4: Preprocess policy files during AgentD launch

**Files:**

- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/NativeRuntime.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionLaunchResult.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`

**Step 1: Write failing launch tests**

Add tests proving that NativeRuntime:

- reads the source DSL before creating a session;
- writes `sandbox-policy.cbor` inside the new session directory;
- passes the generated path, never the source path, after `--sandbox-policy`;
- returns `INVALID_SPEC` without launching for syntax or exactness errors;
- removes the session directory if writing the compiled file fails;
- leaves the command unchanged when no policy was requested.

**Step 2: Run the focused runtime test and verify it fails**

```bash
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.runtime.NativeRuntimeTest'
```

Expected: FAIL because NativeRuntime still passes the source file directly.

**Step 3: Integrate the parser, compiler, and writer**

Compile after ordinary `SessionSpec` and workspace validation but before
creating the session directory. Create the directory only after compilation
succeeds, atomically write the compiled file, and pass its path into a changed
`command(...)` helper:

```java
private List<String> command(
        SessionSpec spec,
        Path workingDirectory,
        Path sessionDirectory,
        Optional<Path> compiledPolicy
)
```

Map `PolicyException` to `INVALID_SPEC`. Map output I/O to `LAUNCH_FAILED` and
use the existing cleanup path. Keep `run-unsandboxed` only when a compiled
policy is present.

**Step 4: Run AgentD runtime and sandbox tests**

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.runtime.NativeRuntimeTest,pro.deta.orion.agentd.sandbox.*Test'
```

Expected: PASS.

**Step 5: Commit**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/runtime \
  agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java
git commit -m "Preprocess sandbox policies in AgentD"
```

### Task 5: Decode and validate compiled policies in Rust

**Files:**

- Create: `session-host/src/sandbox.rs`
- Modify: `session-host/src/lib.rs`

**Step 1: Write failing decoder tests in `sandbox.rs`**

Decode the shared fixture with `include_str!`, then reject indefinite arrays,
non-shortest integers, an unknown version, the wrong handled mask, zero or
unknown grant bits, relative paths, duplicate or unsorted paths, invalid UTF-8,
trailing data, and each size limit.

**Step 2: Run Rust tests and verify they fail**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: FAIL because `crate::sandbox` does not exist.

**Step 3: Implement the fixed-schema decoder**

Keep the public model narrow:

```rust
pub(crate) const HANDLED_FS_RIGHTS: u64 = (1 << 17) - 1;

pub(crate) struct CompiledPolicy {
    pub(crate) version: u64,
    pub(crate) rules: Vec<CompiledRule>,
}

pub(crate) struct CompiledRule {
    pub(crate) path: PathBuf,
    pub(crate) rights: u64,
}
```

Implement only definite arrays, canonical unsigned values, and text needed by
the contract. Read a bounded regular file without following its final symlink.
Return `HostError::Policy` with field-oriented details.

**Step 4: Run `make session-host-test`**

Expected: PASS on the development platform.

**Step 5: Commit**

```bash
git add session-host/src/sandbox.rs session-host/src/lib.rs
git commit -m "Decode compiled Landlock policies"
```

### Task 6: Prepare and apply Landlock only to the PTY child

**Files:**

- Modify: `session-host/Cargo.toml`
- Modify: `session-host/Cargo.lock`
- Create: `session-host/src/platform/sandbox.rs`
- Modify: `session-host/src/platform/mod.rs`
- Modify: `session-host/src/platform/unix.rs`
- Test: `session-host/tests/linux_landlock.rs`

**Step 1: Add failing platform behavior tests**

On non-Linux Unix, assert that a valid requested policy fails by default and
runs only with `run-unsandboxed`. Under `cfg(target_os = "linux")`, fork a test
child and verify:

- an allowed existing file can be read and modified;
- a denied sibling cannot be opened;
- `read-dir` is independent from `read-file`;
- creation and deletion require their individual directory rights;
- a grandchild inherits the restriction;
- the parent host can still update its session directory.

Skip only when the test kernel reports ABI lower than 9; do not reinterpret a
partial ruleset as a pass.

**Step 2: Run Rust tests and observe the current placeholder failure**

```bash
make session-host-test
```

Expected: the new enforcement tests FAIL because the host rejects every
requested policy.

**Step 3: Add the pinned Linux-only dependency**

```toml
[target.'cfg(target_os = "linux")'.dependencies]
landlock = "=0.4.7"
```

Update `Cargo.lock` using the repository-pinned Cargo toolchain.

**Step 4: Build the prepared ruleset before `forkpty`**

Use `AccessFs::from_all(ABI::V9)` and
`CompatLevel::HardRequirement`. Convert masks with checked `BitFlags`
construction, create the ruleset, open each path without following its final
symlink, and add `PathBeneath` rules. Classify only missing platform/kernel/LSM
support as unavailable; malformed input and rule errors remain fatal.

On an explicit unavailable fallback, return a prepared no-op plus metadata
showing no enforcement. On default `fail`, return `HostError::Policy` before
the child exists.

**Step 5: Restrict the child between release and `exec`**

Pass `PreparedSandbox` by value into `spawn_pty`. Add a close-on-exec child
setup pipe alongside the existing tracker release pipe. In the `pid == 0`
branch, after the tracker release byte and before `exec_child`, set
`PR_SET_NO_NEW_PRIVS` and consume the prepared ruleset with `restrict_self()`.
Require `RulesetStatus::FullyEnforced` and `no_new_privs == true`; otherwise
write a fixed failure code to the setup pipe and `_exit(127)`. Successful
`exec` closes the pipe; the parent must observe that EOF before `spawn_pty`
returns. This prevents AgentD from accepting a host whose child never acquired
the sandbox. The parent only drops its copy of the ruleset descriptor.

**Step 6: Run native tests and a Linux compile check**

```bash
make session-host-test
```

Expected: PASS. On Linux, the enforcement tests PASS or explicitly report an
ABI-below-9 skip. On macOS, add the Linux std target to the pinned toolchain and
run a Cargo `check --target x86_64-unknown-linux-gnu`; expected: PASS without
linking.

**Step 7: Commit**

```bash
git add session-host/Cargo.toml session-host/Cargo.lock \
  session-host/src/platform session-host/tests/linux_landlock.rs
git commit -m "Enforce Landlock in the session child"
```

### Task 7: Persist the effective granular sandbox description

**Files:**

- Modify: `session-host/src/journal.rs`
- Modify: `session-host/src/platform/unix.rs`
- Modify: `session-host/protocol/fixtures/metadata-v1.json`
- Modify: `session-host/protocol/README.md`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionManifest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/JsonSessionManifestReader.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/JsonSessionManifestReaderTest.java`

**Step 1: Write failing Rust and Java metadata tests**

Retain required `readWritePaths` and `readOnlyPaths` for metadata v1
compatibility. Add `policyVersion`, `handledRights`, and ordered `rules`, where
each rule contains a path and symbolic rights. Assert that legacy metadata
without the new optional fields still reads, while a newly written enforced
session exposes the complete effective policy.

**Step 2: Run the focused tests and verify they fail**

```bash
make session-host-test
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.JsonSessionManifestReaderTest'
```

Expected: FAIL because the granular metadata fields are not modeled.

**Step 3: Extend metadata compatibly**

Add serde defaults for the new Rust fields. Populate legacy path lists only
for rules exactly matching the old `rw` or `ro` masks; the new `rules` array is
authoritative. Extend the strict Java reader with bounded optional fields and
immutable records. Keep unknown future JSON fields skippable.

**Step 4: Regenerate and verify metadata fixtures**

Run `make session-host-fixtures`, inspect only intended fixture changes, then
run both commands from Step 2. Expected: PASS.

**Step 5: Commit**

```bash
git add session-host/src session-host/protocol \
  agentd/src/main/java/pro/deta/orion/agentd/session \
  agentd/src/test/java/pro/deta/orion/agentd/session/JsonSessionManifestReaderTest.java
git commit -m "Record effective Landlock policy metadata"
```

### Task 8: Verify the complete launch boundary

**Files:**

- Modify: `session-host/tests/unix_process_host.rs`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`
- Modify: `docs/plans/current-work/native-session-host/linux-sandbox/TASK.md`

**Step 1: Add the end-to-end regression scenarios**

Exercise the generated compiled policy path through NativeRuntime and the
native host boundary. On Linux, launch a shell that accesses an allowed
workspace and denied credential subtree from both the direct child and a
grandchild. Verify the host journal and control endpoint remain usable. On
portable Unix, retain the explicit unavailable fallback scenario.

**Step 2: Run focused verification**

Run outside the sandbox:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.runtime.NativeRuntimeTest,pro.deta.orion.agentd.sandbox.*Test'
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.session.JsonSessionManifestReaderTest'
make session-host-test
```

Expected: PASS.

**Step 3: Run routine development verification**

```bash
mvn verify -Pdev -T 4
git diff --check
```

Expected: BUILD SUCCESS and no whitespace errors.

**Step 4: Request review and address only verified findings**

Use `superpowers:requesting-code-review`, apply `docs/reviews/RULES.md`, fix
blocking findings, and repeat the smallest relevant tests after every fix.

**Step 5: Prepare the dedicated-worktree completion commit**

Follow the repository worktree rules: remove this leaf directory and its link
from `docs/plans/current-work/native-session-host/TASK.md`, squash all unique
task commits, and use exactly:

```text
Enforce Linux Landlock sandbox [task: native-session-host/linux-sandbox]
```

Cherry-pick the single commit to `main`, run `make test` there, and remove the
worktree and task branch only after the main worktree is clean and verified.
