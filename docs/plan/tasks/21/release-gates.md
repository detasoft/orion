# Orion Release Gates and Daily Verification

## Scope and evidence

This report records execution of the daily instructions in
[Orion: daily plan](https://chatgpt.com/g/g-p-6af22ad76c3c8191b975d85daf9e1b5a-orion/c/6ade3563-09bc-83eb-8da9-ecb2a9443c71),
starting with the original discussion on 25 October 2026. Daily instructions must
be processed chronologically without dropping repeated requirements. The full
daily inventory is being recovered from the source conversation; coverage is
incomplete until every dated instruction has been reconciled below.

The authoritative unfinished implementation tasks remain under
`docs/plans/tasks/21_pre-release-review/`. This evidence report does not replace
that task tree or declare its acceptance criteria complete.

Current candidate: `a6bb30c37d4def9dfe8e5782ccd76b83220f9394` (`main`).
Verification started on 12 November 2026 on macOS/Darwin arm64, Corretto JDK
21.0.2, the published Detasoft mvnd `2.0.0-rc-4-deta-1` wrapper, and build cache
extension `net.detasoft.maven.extensions:maven-build-cache-extension:1.3.1-deta-1`.
This host has existing toolchains and dependency caches; it is not a clean VM.

`PASS` requires observed evidence for the stated scope and revision. `FAIL`
records an observed failure. `NOT TESTED` records an outstanding verification.
`IN PROGRESS` is not a passing release gate. Historical results do not certify
the current candidate. No Orion public release is authorized by this report.

## Current checks

| Check | Status | Evidence and limits |
| --- | --- | --- |
| Full dev-profile package and unit tests, build cache disabled | PASS | `make test MAVEN='./mvnw -Dmaven.build.cache.enabled=false' LOG=/tmp/orion-published-build-check-tests.log`, exit 0. Includes Maven-managed frontend and Rust lifecycle executions; not the integration-test lifecycle. |
| Dev-profile package with smart builder and cache enabled | PASS | `make test MAVEN='./mvnw -Dmvnd.builder=smart' LOG=/tmp/orion-published-build-check-smart.log`, exit 0. Cached execution is not a substitute for the uncached test result. |
| Dev-profile package with concurrent builder and cache enabled | PASS | `make test MAVEN='./mvnw -Dmvnd.builder=concurrent' LOG=/tmp/orion-published-build-check-concurrent.log`, exit 0. Build success alone does not certify cache effectiveness. |
| Public extension resolution | PASS | Orion `validate -N -Pdev` with an initially empty `/tmp/orion-central-extension-resolution` Maven repository, exit 0; POM and JAR downloaded from Central. Log: `/tmp/orion-central-extension-resolution.log`. |
| Ordinary distribution build, tests enabled and cache disabled | PASS | `make dist MAVEN='./mvnw -Dmaven.build.cache.enabled=false -l /tmp/orion-task21-20261112-dist.log'`, exit 0, `BUILD SUCCESS`, 2 min 24 s Maven wall clock. No test-skipping option was used. |
| Extracted archive executable permissions and help | PASS | Fresh extraction to `/tmp/orion-task21-install-tu1c8fb4`; `orion/bin/orion` and `orion/runtime/bin/java` have executable mode `0755`. Direct `orion/bin/orion --help` exited 0 without chmod or a Java override. Does not establish service startup, Git behavior, or recovery. |
| Isolated packaged first start and restart | PASS | `/tmp/orion-task21-runtime-wct8hmog`, fresh temporary state and loopback ports. First start explicitly used `--create-if-missing`; restart did not. Both served the HTTP schema (200, 3202 bytes) and SSH banner `SSH-2.0-APACHE-SSHD-2.13.2`. Both processes were stopped after readiness checks. Password stayed in process memory; logs omit password/token lines. Enrollment, authenticated Git operations, and backup/restore were not exercised. |
| Full integration and cross-engine matrix, candidate `7afc159a` | FAIL | Awake uncached rerun completed with mvnd exit 1, Make exit 2: 4,229 JVM tests, three failures, one error, six skipped. Detailed outcomes below. |
| Focused historical SSH configuration-push integration check | FAIL | The reactor stopped in a prerequisite unit test before the selected integration test ran. `/tmp/orion-task21-20261112-ssh-config.log`: `GitSmartHttpClientTransportTest.permitsDiscoveryBodyThatMakesProgressBeforeEachReadTimeout:312` returned a discovery read timeout instead of success. This is not evidence of an SSH scenario failure. |
| SSH configuration-push retry after timing fix, candidate `dff458b8` | FAIL | The selected integration method executed: one test, one failure, zero errors/skips, 1.713 s class time. `GitSshTransportEndToEndIT.directConfigurationPushRetainsValidStateUntilAValidCommitArrives:189` expected `invalid`, observed `unavailable`. Log: `/tmp/orion-task21-ssh-config-after-timing.log`. |
| Backup/restore, upgrade/rollback, clean-host installation | NOT TESTED | Current packaged first-start/restart checks use this existing macOS host. They do not establish recovery, upgrade/rollback, or a clean Linux installation. |
| Security, dependency/license inventory, final artifact notices | NOT TESTED | Existing documentation and historical findings must be checked against current code and the actual archive. |

### Distribution artifact

- Path: `core/bootstrap/target/orion-dist.tar.gz`.
- Size: 82,434,272 bytes.
- SHA-256: `3213b968c68664aa6e6888d7d2bea77a6ea9c3dddfa051784613631be9e9959f`.
- Source: candidate `a6bb30c37d4def9dfe8e5782ccd76b83220f9394`.
- Installation directory: `/tmp/orion-task21-install-tu1c8fb4`.
- Isolated first-start/restart configuration and sanitized logs:
  `/tmp/orion-task21-runtime-wct8hmog`.
- Public download, clean Linux host installation, enrollment, backup/restore,
  and upgrade/rollback remain unverified. First initialization and restart were
  checked on this macOS host as described above.

### Static artifact and metadata observations

The archive's `lib/orion.jar` includes all seven notices required by
`RELEASES.md`: `LICENSE`, `LICENSE-BUSL-1.1.txt`,
`LICENSE-AGPL-3.0-or-later.txt`, `NOTICE.md`, `THIRD_PARTY_NOTICES.md`,
`TRADEMARKS.md`, and `RELEASES.md`, under `META-INF/`. No archive entry name
containing `sbom`, `cyclonedx`, or `spdx` was found. This filename check does not
establish that an external dependency inventory exists or matches the archive.
The limited PEM-marker scan in key/certificate/configuration entries found no
private-key markers; it is not a complete secret audit.

The candidate still declares Maven `1.0-SNAPSHOT` and frontend `0.1.0`.
`RELEASES.md` correctly remains unreleased; no public distribution date was
invented. `make/server.mk` still prints the administrative token in
`check-jetty-git` and `check-ssh-git`. Those goals have not been run for this
report. Their token output requires repair before retaining their smoke logs.

A parallel session committed `27a9b75c` while the HTTP-test verification was
being prepared. It changes dependency-repository handling and is not part of
the original `a6bb30c3` archive evidence. The final HTTP-test pre-commit run is
against `27a9b75c` plus the two timing edits; results for these revisions must
not be conflated.

### HTTP test timing investigation

The prerequisite HTTP test failed once during the focused integration reactor
run although the earlier full unit and ordinary distribution builds passed on
the same candidate. An immediate isolated rerun passed (one test, no failures,
errors, or skips); the initial failure remains part of the evidence.

The method used a 100 ms read timeout and 60 ms between three response chunks.
At the user's request, only this method now uses a 1 s timeout and 600 ms between
chunks. The complete transfer still exceeds one read timeout, preserving the
test's distinction between read inactivity and total operation duration. The
whole HTTP transport test class passed: 15 tests, zero failures/errors/skips,
2.112 s. Log: `/tmp/orion-task21-http-timeout-method-class.log`.
The required full uncached unit suite passed (exit 0), with log
`/tmp/orion-task21-http-timeout-full-resume.log`. The earlier invocation was
interrupted and its exit status could not be recovered, so it is not counted as
a passing check. The timing edits were committed separately as `dff458b8` on top
of `27a9b75c`. No production timeout was changed, and an isolated pass does not
establish the original failure's root cause or prove that timing-related flakes
can never recur.

The subsequent SSH retry used:

```sh
make test-all MODULE=tests/integration-test \
  TEST='GitSshTransportEndToEndIT#directConfigurationPushRetainsValidStateUntilAValidCommitArrives' \
  MAVEN='./mvnw -Dmaven.build.cache.enabled=false -Dtest=GitSmartHttpClientTransportTest -Dsurefire.failIfNoSpecifiedTests=false' \
  LOG=/tmp/orion-task21-ssh-config-after-timing.log
```

After the full uncached unit suite passed, this retry restricted repeated unit
coverage to the HTTP transport class while selecting the SSH integration method
through Failsafe. It did not skip the selected integration test. The reactor
returned Make exit 2 due to the reproduced SSH state mismatch above; this check
does not establish all other SSH scenarios or the complete integration matrix.

### CI visibility

Read-only GitHub API queries on 12 November returned zero Actions workflows.
Published `main` was `a6bb30c37d4def9dfe8e5782ccd76b83220f9394`, committed at
16:18:49 UTC. Local `27a9b75c` and `dff458b8` were newer and are not covered by
that remote revision. No external CI provider was ruled out, and no remote
checks or branch protection were modified. Absence of Actions workflows alone
does not certify the absence of every possible CI system.

### Source-plan fidelity

The source chat created
[`daily-plans.md` in `codex/task21-daily-plans`](https://github.com/detasoft/orion/blob/c24b4a306b30a86ed1f6458126c92ce47d29b3f6/docs/plan/tasks/21/daily-plans.md).
Its commit is `c24b4a306b30a86ed1f6458126c92ce47d29b3f6`. The archive itself says
that 26 October through 5 November and the roadmap are reconstructed. It must
not be treated as an authoritative transcript for those dates.

A demonstrated discrepancy: the original 4 November message visible in the
shared chat requests the SSH invalid-configuration regression, distribution
verification, and progress evidence. The reconstructed 4 November entry instead
describes backup/restore preparation. The original roadmap also assigns public
release/documentation to week 4, presentation to week 5, and first customers to
week 6; the reconstruction changes those milestones. Faithful source text is
needed before chronological coverage can be certified. The user has offered to
provide the original text. The reconstructed file has not been integrated into
the local candidate or used to mark any day complete.

The owner subsequently supplied the original 28–30 October messages. Their
faithful translations are now in [daily-plans.md](daily-plans.md); the earlier
reconstructions for those dates are superseded. The original 26–27 October
messages remain unavailable, so those dates are explicitly open rather than
silently skipped.

## Chronological execution

### 2026-10-25: original scope and technical audit

The initial discussion proposes a first public developer preview and asks for an
audit before committing to missing features or a final release scope.

| Requirement | Status | Evidence / remaining work |
| --- | --- | --- |
| Audit architecture, implemented features, and unfinished work | IN PROGRESS | Read the original conversation and the current Task 21 composite and leaves. Reconcile source, tests, and operator documentation before assigning feature readiness. |
| Verify tests, CI/CD, error handling, and security | IN PROGRESS | Current uncached dev-profile test run passed. Integration, current CI coverage, and security evidence remain open. |
| Verify installation, configuration, distribution, and recovery | IN PROGRESS | Ordinary distribution build, extracted launcher help, first initialization, and restart passed. Enrollment, authenticated use, clean-host installation, and recovery remain open. |
| Identify concrete P0/P1/P2 release blockers | NOT TESTED | Separate observed current failures from historical failures and unverified claims. Do not infer a blocker solely from an old report. |
| Define and freeze the supported preview scope | NOT TESTED | The conversation identifies Git clone/fetch/push, repository creation, authentication, ACL, installation/update, and data recovery as candidates. Product version, accepted risks, external operator, and support commitments require owner decisions where not already recorded. |
| Preserve the six-week roadmap separately from daily execution | IN PROGRESS | A complete chronological plan export was requested in the source chat for `daily-plans.md`; no plan item is marked complete by that request. |

### 2026-10-26 and 2026-10-27: source gap

Original daily messages have not been supplied. No reconstructed instructions
are executed or marked complete on their behalf. Work below establishes useful
release evidence without claiming an uninterrupted completed chronology.

### 2026-10-28: clean distribution, contents, and first-user PULL

| Assignment | Status | Evidence / remaining work |
| --- | --- | --- |
| Task 21.1: build from a clean checkout and record SHA/result/artifact | FAIL (audit outcome established) | Local clone `/tmp/orion-task21-clean-7afc159a`, SHA `7afc159ad7b284b2c24b9e26d3c06f2225b31bee`; initial `git status --short` empty. Ordinary `make dist` with tests and build cache disabled returned Make exit 2, Maven `BUILD FAILURE`, 1 min 3 s. AgentD had 333 tests, 9 failures, zero errors/skips. The live native-host tests expected the absent `agent/session-host/target/cargo/debug/session-host`. Log: `/tmp/orion-task21-clean-7afc159a-dist.log`. No archive produced. Shared installed toolchains/dependency caches remain an explicit limit. |
| Task 21.5: version, configuration, launch scripts, and notices | BLOCKED (failure alternative recorded) | No clean-build archive exists; the source plan explicitly permits recording the build cause instead of inspecting a missing artifact. Earlier `a6bb30c3` archive results remain separately identified above and are not attributed to `7afc159a`. Maven/UI version mismatch and exact-artifact dependency inventory remain open. |
| First-user PULL scenario | PARTIAL | Owner confirms: first user is the owner; required job is proxying Git to GitHub through Orion. S3 is optional, desirable rather than mandatory for `v0.1.0`. Urgency and proven limitations of alternatives were not supplied and are not invented. |

The concrete preview scenario is: the owner accesses a GitHub-backed repository
through Orion and can verify the advertised Git operations against the upstream
commit history. Existing local-repository push/clone evidence alone cannot
certify GitHub proxying. README's system-proxy behavior and bootstrap-proxy
tests are existing mechanisms to inspect; an actual GitHub repository, required
credentials, supported directions, and acceptance must be identified before a
real GitHub smoke run. No external repository was modified for this report.

The clean-build cause is localized: the `dist` profile in
`agent/session-host/pom.xml` sets `rust.release=true`, producing the release
binary. AgentD's live-peer tests explicitly open the debug-path binary, for
example `NativeControlLivePeerTest.extractSessionHost()` at line 412. A working
checkout with a preexisting debug binary can therefore pass while a clean
distribution build fails. The earlier successful prepared-checkout build did
not expose this prerequisite. No copied binary, prebuild, skipped test, or
fallback path was used to hide the clean-build failure.

### 2026-10-29: localize SSH state and run the complete matrix

| Assignment | Status | Evidence / remaining work |
| --- | --- | --- |
| Identify the component producing the SSH configuration status mismatch | PASS (localization only) | Current `dff458b8` failure reproduced, expected `invalid`, observed `unavailable`. `BlobOrionConfigurationStorage.read()` maps XML codec errors to `Result.Failure(GENERAL)`; `OrionConfigurationEditor.status()` maps every failed document load to `unavailable`. The malformed XML is therefore classified as unavailable after a successful repository push/read. Stored and prior active revision assertions preceding the validation assertion passed. No behavior fix is claimed. |
| Complete `make test-all` on one identified revision | FAIL / incomplete matrix | `make test-all MAVEN='./mvnw -Dmaven.build.cache.enabled=false' LOG=/tmp/orion-task21-clean-7afc159a-test-all.log`, same isolated `7afc159a` checkout, Make exit 2. The log records `RemoteAgentdProvisionerTest.detachedAgentdUsesBundledSessionHostAfterSshCloses:166` with a provisioning SSH operation timeout; the client then reports no daemon message within 3000 ms and Make reports mvnd error 100. No complete green integration matrix is established. |

The localization points to configuration-load/error classification and the
editor's status projection, rather than SSH authentication. It does not prove
the subsequent valid repair activates successfully: the method stops at the
earlier failed assertion. A repair must preserve last-valid configuration and
distinguish malformed content from missing/unreadable source data.

The full-run provisioning report has 68 module tests with one error and no
failures/skips. The failing class reports eight tests with one error; the error
is `ProvisioningException: SSH provisioning operation timed out`. Follow-up
diagnostics below establish host suspension during this run; a focused awake
rerun passed. This does not establish a green complete matrix.
Remaining reactor coverage cannot be inferred from an aborted daemon stream.

#### Follow-up: apparent hang and daemon disconnect

The macOS power log records clamshell sleep at 18:31:25 on 12 November,
followed by short maintenance wakes and repeated sleep until lid wake at
20:39:54. Daemon `23cd709d` heartbeat gaps match those sleep intervals.
For example, it logged heartbeats at 18:48:45 and then 19:03:59;
macOS slept at 18:48:43 and woke at 19:03:59. The provisioning error was
dispatched at 18:48:38, exactly the wake following 863 seconds of sleep.
These timings strongly implicate suspension in the timeout rather than a
continuously running 861-second SSH operation.

The mvnd client waits for a heartbeat for 3000 ms. Its communication-failure
path removes the daemon from the registry. The daemon recorded a broken pipe
at 19:20:35, a registry-related stop request at 19:20:40, and completion of
the abandoned build at 19:28:59. It subsequently accepted the S3 request.
A thread dump at 20:56:51 showed normal idle server waits and no active
builder threads. There is no evidence of a persisting Maven deadlock.
The exact client scheduling at wake was not captured, so suspension-related
heartbeat expiry is a supported explanation, not a controlled reproduction.

The same checkout and mvnd passed this focused awake rerun, Make exit 0:

```sh
make test MODULE=agent/agent-provisioning \
  TEST='RemoteAgentdProvisionerTest#detachedAgentdUsesBundledSessionHostAfterSshCloses' \
  MAVEN='./mvnw -Dmaven.build.cache.enabled=false' \
  LOG=/tmp/orion-task21-provisioning-awake.log
```

Surefire records one test, zero failures/errors/skips, class time 1.150 s.
The previous failure remains recorded; this rerun does not prove immunity
to other timing faults. Further full-run evidence must come from an awake
host. Sleep/wake resilience of the mvnd connection remains a separate
tooling concern; no heartbeat thresholds or test timeouts were changed.

### 2026-10-30: explain SSH/S3 failures and record next steps

| Assignment | Status | Evidence / remaining work |
| --- | --- | --- |
| Explain whether SSH failure belongs to validation, activation, or API reply | PASS (localization only) | Same reproduced configuration-load/status classification path as 29 October. Repeated requirement retained explicitly; no fix or complete recovery scenario is claimed. |
| Classify S3 metadata rejection failure | PASS (current focused check) | Same `7afc159a` checkout, same mvnd, cache disabled. Failsafe executed `S3NativeGitRepositoryProviderIT#listsAllPagesAndRejectsCorruptOrMisplacedMetadata`: one test, zero failures/errors/skips, method time 4.614 s. The historical failure was not reproduced; no historical root cause is claimed. |
| Decide whether S3 is mandatory | PASS (owner decision) | Owner explicitly says S3 is not mandatory for `v0.1.0`, but desirable. This does not hide or excuse failing tests. |
| Preserve outcomes, cause, and next step in Task 21 | IN PROGRESS | This report retains exact methods, revisions, logs, limits, configuration-status localization, host-suspension diagnostics, and focused S3 success. A complete green matrix remains outstanding. |

The focused S3 command used `make test-all MODULE=git/git-s3-storage` with
the method above, `MAVEN='./mvnw -Dmaven.build.cache.enabled=false'`, and
`LOG=/tmp/orion-task21-clean-7afc159a-s3-metadata.log`. The daemon accepted
it at 20:40:48, entered Failsafe at 20:41:53, and finished with exit code 0
at 20:41:58. The empty log was initially mistaken for a queued build.
With `-q`, this successful run emitted no textual messages: mvnd's file log
stores textual output, not lifecycle or heartbeat events. The daemon events
and Failsafe XML establish actual execution. No ordinary-Maven bypass was
used and no daemon was killed.

### Awake complete-matrix rerun for 29 October

On the unchanged clean-checkout candidate `7afc159a`, the following run reached
normal build completion at 21:05:41, with mvnd exit 1 and Make exit 2:

```sh
caffeinate -i make test-all \
  MAVEN='./mvnw -Dmaven.build.cache.enabled=false' \
  LOG=/tmp/orion-task21-7afc159a-awake-test-all.log
```

Fresh Surefire/Failsafe XML reports contain 4,229 JVM test cases: 4,219 passed,
three failed, one errored, six skipped. Copies were preserved before focused
reruns under `/tmp/orion-task21-awake-matrix-reports`, including `summary.json`.
These counts exclude frontend and Rust cases. No complete green run is claimed.

| Failing method | Observed result | Next step |
| --- | --- | --- |
| `GitSshTransportEndToEndIT.directConfigurationPushRetainsValidStateUntilAValidCommitArrives` | Expected `invalid`, received `unavailable` | Repair configuration-load/status classification while retaining last-valid active state; localization is recorded above. |
| `BootstrapProxyEndpointIT.scopedClientsCloneAndPushThroughAliasAcrossRestartWhileCacheRemainsPrivate`, HTTP and SSH parameters | Both expected HTTP 409, received 201 at line 178 | Reproduce separately and establish whether the expected conflict and request revision are valid before changing the API or assertion. |
| `OrionStartupIT.restartUsesExistingStorageWithoutRewritingAcl` | `Cannot open repository orion`, caused by H2 `refs.mv` lock and `OverlappingFileLockException` | Reproduce separately; inspect concurrent runtime access versus the test's independently opened repository provider. |

Skipped cases belong to `ReferenceGitControlMatrixIT` (one),
`PlaywrightExternalServicesIT` (one), `IntegrationCloudflareIT` (one),
`DistributionArchiveIT` (two, distribution profile absent), and
`GitBlobStorageTest` (one). Their exclusion remains explicit. The separate
distribution-profile run below executed both archive tests without skips.

`BootstrapProxyTransportIT` passed both HTTP/SSH parameters, and
`S3BootstrapProxyColdStartIT` passed. This establishes local-upstream behavior,
not a real GitHub smoke test. The public proxy endpoint scenario still fails.

### 2026-10-31: packaging confirmation and week 2 priorities

The owner supplied the original 31 October message; it is preserved in
[daily-plans.md](daily-plans.md). The source gaps for 26–27 October remain open.

The same `7afc159a` checkout passed the archive check with mvnd exit 0:

```sh
caffeinate -i make test-all MODULE=core/bootstrap TEST=DistributionArchiveIT \
  MAVEN='./mvnw -Pdist -Dmaven.build.cache.enabled=false' \
  LOG=/tmp/orion-task21-7afc159a-dist-archive.log
```

Failsafe reports two tests, zero failures/errors/skips, 2.999 s class time.
The distribution profile executed `create-jlink-distribution` and
`attach-jlink-distribution`; both executable-mode preservation and fresh
extraction with bundled Java were checked. No `chmod` repair was applied to
the extraction. Artifact: 82,434,269 bytes, SHA-256
`69d069dd0178aaa1b093108d479ec247e9a8d7eb6c36e6c19c2837f5e23e4200`.

This checkout had already run dev builds, so its debug session-host binary
was present. Archive-test success does not resolve the earlier clean `dist`
failure or establish byte-for-byte reproducibility across builds.

#### Blocker triage and immediate queue

| Required area | Current classification | Next step | Preview gate |
| --- | --- | --- | --- |
| SSH configuration push | Confirmed status mismatch, localized to malformed-document classification | Preserve last-valid state and distinguish invalid content from unavailable storage, then rerun the full repair sequence | Blocks claiming the advertised configuration failure/recovery contract |
| S3 metadata integrity | Historical failure not reproduced; all five `S3NativeGitRepositoryProviderIT` methods passed in the awake matrix | Retain coverage; do not invent a historical cause | S3 is optional by owner decision; exclude unsupported claims if not shipped |
| npm security findings | Fresh registry audit on 13 November: ten affected packages, three high and seven moderate; production-only audit retains one high `source-map-js` finding | Apply compatible fixes and verify frontend behavior; establish actual exposure in the packaged bundle | Security triage gate remains open; audit severity alone is not proof of packaged exploitability |
| Complete green matrix | Confirmed failing run, with proxy conflict and restart-lock failures in addition to SSH status | Repair reproducible failures and rerun on one final SHA with explicit skips | Blocks release readiness |

Audit commands used the Maven-installed Node/npm under
`net/frontend/ui/target/node/node/`: `npm audit --json` and
`npm audit --omit=dev --json`, both exit 1. No dependencies were modified.
High findings affect `brace-expansion`, `source-map-js`, and `vite`; the
production-only finding is `source-map-js` (GHSA-68fv-2mgg-jv7q).

The immediate repair queue is bounded to three checkpoints:

1. Make first-checkout distribution builds independent of an existing debug
   session-host binary; preserve tests and distribution release binaries.
2. Repair deterministic release-matrix failures in their owners: first the
   independently reproduced startup lock and proxy conflict scenarios, then
   malformed-configuration status. Preserve the original behavioral assertions
   unless investigation demonstrates that their setup or contract is wrong.
3. Resolve npm findings with compatible dependency changes and frontend tests;
   retain explicit exposure/accepted-risk decisions where a fix is unavailable.

A focused Failsafe rerun on the same SHA reproduced both proxy failures and
the startup lock error: three tests, two failures, one error, zero skipped.
Log: `/tmp/orion-task21-7afc159a-failsafe-only.log`. It used the existing Make
goal with the selected integration methods and a Surefire filter matching no
unit tests; this is diagnostic evidence, not another full-suite result.
The startup error occurred on the second start (line 124), rather than line 118
in the full run. Both accesses open a separate provider while the runtime is
active, so test-side ownership and runtime concurrency must be distinguished.

An earlier attempted focused run stopped in prerequisite unit method
`NativeControlLivePeerTest.listsAndSignalsAProcessThroughTheRealHost`:
expected `ControlResult.Processes`, received `Failed(AMBIGUOUS_DELIVERY)` with
`native control operation timed out`. Log:
`/tmp/orion-task21-7afc159a-new-failures.log`. That attempt provides no outcome
for the requested integration methods; the subsequent focused run above does.
This additional native-control timeout remains untriaged and is not dismissed.

#### Startup-test ownership repair

The restart test opened independent native-repository providers while the
runtime could hold an access to the same `refs.mv` file. Its disk assertions
now run after shutdown; during restart it checks that the active configuration
revision equals the original stored commit. The final persisted ACL contents
and HEAD are checked again after shutdown. No production ownership, locking,
or timeout behavior was changed.

The repaired method passed twice in focused Failsafe runs, one test with zero
failures/errors/skips each; the final class time was 1.368 s. Logs:
`/tmp/orion-task21-restart-ownership-green.log` and
`/tmp/orion-task21-restart-ownership-restored.log`. Temporarily restoring the
old test also passed in `/tmp/orion-task21-restart-ownership-mutated.log`;
the lock collision is timing-dependent, not a deterministic failure on every
execution. Earlier full and focused failing evidence remains above.
The required root `make test` with cache disabled also passed, exit 0;
log: `/tmp/orion-task21-restart-precommit.log`. The shared working tree
contained separate release/build changes from another session, so this is
working-tree verification, not a complete integration result for a new clean
candidate. Automatic scoped review found no blocking issues.

#### Proxy-test revision-conflict setup repair

The proxy endpoint test expected HTTP 409 while supplying the current revision
returned by GET after adding root's SSH key. That request correctly received
201. The test now captures the revision before that configuration edit and
asserts that GET reports a different revision. Its first POST uses the stale
revision and must receive 409; the subsequent GET/retry uses the current
revision and must receive 201. No API behavior was changed.

Both HTTP/SSH parameters passed the complete scenario, including scoped
clone/push, denied operations, private-cache checks, and restart: two tests,
zero failures/errors/skips, 16.19 s class time. Log:
`/tmp/orion-task21-proxy-stale-revision-green.log`. This diagnostic checkout
contains the original `7afc159a` candidate plus the startup-test and proxy-test
repairs; it is not a claim that the unchanged candidate passed.

The non-vacuity mutation replaced the first POST's stale revision with the
current GET revision. Both parameters failed exactly at the expected conflict:
`expected: 409, but was: 201`, with no errors/skips. Log:
`/tmp/orion-task21-proxy-stale-revision-mutated.log`. The stale-revision change
was then restored for the final focused verification.
The restored version passed again: two tests, zero failures/errors/skips,
15.77 s class time, Make exit 0. Log:
`/tmp/orion-task21-proxy-stale-revision-restored.log`.

The first required root `make test` failed while Surefire loaded the unchanged
`ConfigurationSecretXmlTest`, with `ClassNotFoundException`; the class file
was present when inspected afterward. Log:
`/tmp/orion-task21-proxy-precommit.log`. This failure is retained separately
from the proxy scenario and its cause is not established by the focused runs.
After a pause, the same required root command passed, exit 0, without an
additional code fix: `/tmp/orion-task21-proxy-precommit-retry1.log`.
Automatic scoped review found no blocking issues. The shared working-tree
verification includes separate release/build work committed by another session
as `0f519f2d`; it does not certify a clean complete integration matrix.

## Historical evidence

- [28 October verification](verification-2026-10-28.md) and
  [follow-up](follow-up-2026-10-28.md) describe older revisions and environments.
- [SSH outcomes](ssh-results.md), [unit-test log](test-log.md),
  [integration-test log](test-all-log.md), and [distribution errors](dist-errors.md)
  retain historical failures that need current reproduction or verified fixes.
- In particular, the historical invalid-configuration SSH push, S3 metadata
  rejection, and archive executable-mode failures are not marked resolved here
  until current evidence establishes their outcomes.
