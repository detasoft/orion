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
| Full integration and cross-engine matrix | NOT TESTED | Requires a current uncached `make test-all` result; the 28 October failures are historical evidence only. |
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

Later days will be entered in source order after the complete plan inventory is
available. Their requirements are not considered executed merely because they
repeat an earlier check. No date gap is silently treated as completion.

## Historical evidence

- [28 October verification](verification-2026-10-28.md) and
  [follow-up](follow-up-2026-10-28.md) describe older revisions and environments.
- [SSH outcomes](ssh-results.md), [unit-test log](test-log.md),
  [integration-test log](test-all-log.md), and [distribution errors](dist-errors.md)
  retain historical failures that need current reproduction or verified fixes.
- In particular, the historical invalid-configuration SSH push, S3 metadata
  rejection, and archive executable-mode failures are not marked resolved here
  until current evidence establishes their outcomes.
