# Module Review: `tests/git-engine-test-support`

## 2. The Git command runner loses process ownership on interruption

**Problem and evidence.**
[runResult](src/main/java/pro/deta/orion/git/workflow/GitCommandRunner.java#L50) terminates its child only
when the ordinary command timeout expires. An interruption instead throws from
[waitFor](src/main/java/pro/deta/orion/git/workflow/GitCommandRunner.java#L107), restores the interrupt flag,
and reaches a finally block that only deletes the output log. The Git process can remain running.
The configured [60-second JUnit deadline](../../pom.xml#L68), passed to the test providers at lines 293
and 313, can interrupt a multi-operation scenario during a command with its own 30-second timeout.
The [harness](src/main/java/pro/deta/orion/git/workflow/GitInteroperabilityHarness.java#L23) then closes
the server and removes the worktree without the runner having stopped its child.

**Contract.** The runner owns the process it starts. Cancellation must release that resource as well as
the temporary log. Preserve successful results, bounded normal timeout handling, command diagnostics,
the original failure and the caller's interrupted status. No contract allowing detached commands was found.

**Minimal repair and validation.** Keep the process in its owning scope and use one bounded termination
path on exceptional exit. Cleanup must still run when the interrupted flag is already set; preserve the
flag afterwards and suppress cleanup failures onto the original failure rather than replacing it.
Add behavioral coverage beside
[GitCliWorkflowClientTest](src/test/java/pro/deta/orion/git/workflow/GitCliWorkflowClientTest.java)
for an interrupted blocking child, ordinary command timeout and successful completion.
Assert child termination and the interruption/error contract, not private implementation structure.

**Alternatives and consequences.** Merely invoking the current interruptible termination helper from
finally can abort cleanup again. Do not allocate a thread per process wait.
Fix this before consolidating the HTTP test helper onto this runner: that helper currently attempts
termination in finally.

**Confidence and priority.** High from the explicit interruption path and test deadline wiring;
no runtime reproduction executed. P2 test isolation/resource ownership, small local lifecycle repair.

## 3. GitEngine wraps a name that every consumer immediately unwraps

**Problem and evidence.** [GitEngine](src/main/java/pro/deta/orion/git/workflow/GitEngine.java#L5)
contains only a validated name. Both
[GitClient.engine](src/main/java/pro/deta/orion/git/workflow/GitClient.java#L9) and
[GitServer.engine](src/main/java/pro/deta/orion/git/workflow/GitServer.java#L9) create it from their
existing `name()` methods. Every production consumer in
[GitMatrixInvocation](src/main/java/pro/deta/orion/git/workflow/GitMatrixInvocation.java#L22)
immediately calls `.engine().name()`; no implementation overrides `engine()`.
The extra identity representation does not separate a client, server or factory boundary.

**Contract.** Preserve nonblank actual names, declared-versus-created identity checks, stable display
names, lazy factories and server cleanup on a mismatch. No separate documented engine-identity contract
was found beyond the name and its validation.

**Minimal repair and validation.** Use the existing `name()` contract and move nonblank validation to the
invocation boundary; remove the wrapper and forwarding methods together with all internal consumers.
Preserve
[invocation behavior tests](src/test/java/pro/deta/orion/git/workflow/GitInteroperabilityMatrixTest.java#L287)
for names, lazy creation, client mismatch and server cleanup, adapting their API usage.

**Alternatives and consequences.** A new identity interface or cached wrapper would retain the same
indirection. This changes only an internal test-support Java API, with no scenario or wire contract change.

**Confidence and priority.** High from repository-wide construction and consumer searches.
P3 easy removal of a type and two redundant accessors.

## 4. Snapshot capture retains an unused compatibility overload

**Problem and evidence.**
[RepositorySnapshot.capture(Path, String)](src/main/java/pro/deta/orion/git/workflow/RepositorySnapshot.java#L51)
is deprecated, ignores its `ignoredHead` argument and forwards to `capture(Path)`.
Repository-wide searches found no caller. Real
[GitWorkTree](src/main/java/pro/deta/orion/git/workflow/GitWorkTree.java#L68) and
[GitServer](src/main/java/pro/deta/orion/git/workflow/GitServer.java#L29) consumers use the path overload;
Orion adapters also use the repository overload. The unused signature suggests caller-supplied HEAD
affects the observed snapshot.

**Contract.** HEAD, refs, history, tree modes, object IDs and content hashes come from the observed
repository. No compatibility requirement for the discarded argument was found; repository rules require
removing replaced internal paths.

**Minimal repair and validation.** Delete only the deprecated overload.
Preserve
[snapshot behavior coverage](src/test/java/pro/deta/orion/git/workflow/GitInteroperabilityMatrixTest.java#L237)
and [JGit observation](src/test/java/pro/deta/orion/git/workflow/JGitReferenceAdaptersTest.java#L79).
Compile dependent modules to catch missed consumers; add no method-absence test.

**Alternatives and consequences.** An alias or migration flag preserves an unused path.
Deletion affects only a source signature with no in-repository consumers; supported capture behavior
and snapshot representation remain unchanged.

**Confidence and priority.** High from the forwarding body and repository-wide callers.
P3 trivial deletion; no runtime uncertainty in the overload's behavior.
