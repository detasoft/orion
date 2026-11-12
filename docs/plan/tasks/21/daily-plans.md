# Orion Daily Release Plans: Verified Source Archive

This file preserves assignments, not execution results. Results belong in
[release-gates.md](release-gates.md). Every item remains uncompleted here.
The active implementation task tree remains `docs/plans/tasks/`.

## Source fidelity and chronology

The owner supplied the original messages for 28–31 October 2026 in the
local Codex conversation. The entries below are faithful English translations
of those messages. Historical repository observations remain attributed to the
original message; they are not current verification results.

The earlier remote archive in commit
`c24b4a306b30a86ed1f6458126c92ce47d29b3f6` reconstructed early days and changed
some assignments. Those reconstructions are not imported here. The original
25 October discussion was read directly, but the complete 26–27 October daily
messages and a verified continuous archive of later days are still needed.
Missing source days must not be invented, silently skipped, or marked complete.

## Wednesday, 28 October 2026

Week 1 of 6: MVP audit. Goal: public release `v0.1.0`.

The original message reported checking `detasoft/orion` through GitHub. Its last
observed `main` commit was `7c09941` from 27 October, with recent changes to tests
and internal APIs. Task 21 release checks were not marked complete. The author
had not seen local test results and did not claim they passed.

Focus: determine whether Orion can be installed by its first user.

### 1. Build the distribution: Task 21.1

From a clean checkout, capture the SHA, working-tree state, distribution build
log, and exit code. The original commands were:

```sh
git rev-parse HEAD
git status --short
make dist > orion-dist.log 2>&1
echo "Exit code: $?"
```

These commands are historical source text. Current repository instructions
require Maven `-l` instead of shell redirection when executing the build.

**Ready when:** source SHA, build result, and artifact location are known. On
failure, retain the log and identify the first blocker; a fix is not required
to establish the audit result.

### 2. Inspect distribution contents: Task 21.5

If the build succeeds, check its version, configuration, launch scripts, and
required licenses. Compare with `RELEASES.md`, which the original message said
still declared `1.0-SNAPSHOT`.

**Ready when:** there is a concise list of publishable contents and missing
items. If the build fails, record the concrete cause instead.

### 3. Define the first user's PULL scenario: 10 minutes

Record who the first Orion user will be, which job they need to do, why it must
be solved now, and why GitLab, Gitea, or Forgejo are insufficient for that user.

**Ready when:** one concrete scenario can guide the sufficiency of `v0.1.0`
without adding features.

**Day success:** one verifiable packaging result and a clear first-user target.
In the evening, record the SHA, `make dist` result, discovered problems, and
commit links in Task 21. An established build failure is useful audit evidence.

## Thursday, 29 October 2026

Week 1 of 6: close MVP blockers. Goal: `v0.1.0`.

The original message reported `main` at `8812bdc`, titled
`Fix executable permissions in distribution archives`. It described successful
packaging tests and `make test` in Task 21, but earlier failures in full
`make test-all` and one SSH E2E test. It did not claim fresh test execution.

### 1. Investigate the configuration-push failure

The earlier
`GitSshTransportEndToEndIT.directConfigurationPushRetainsValidStateUntilAValidCommitArrives`
expected `invalid` but returned `unavailable`. The original command was:

```sh
make test MODULE=tests/integration-test \
  TEST='GitSshTransportEndToEndIT#directConfigurationPushRetainsValidStateUntilAValidCommitArrives'
```

This is preserved as source text. In the current build, this class is a Failsafe
integration test: execute the applicable `make test-all MODULE=... TEST=...`
goal and verify that the selected method actually ran.

**Ready when:** the test passes on the current SHA, or the failure is reproduced
and the component returning the incorrect state is identified. A fix that day
is optional.

### 2. Repeat the complete test suite on one commit

```sh
git rev-parse HEAD
make test-all LOG=/tmp/orion-test-all-20261029.log
```

Pay particular attention to the S3 integration method
`listsAllPagesAndRejectsCorruptOrMisplacedMetadata`, which failed on 28 October.

**Ready when:** source SHA, exit code, and remaining failures are recorded. Only
a complete successful run establishes a green `test-all` for that commit.

**Day success:** determine whether Git/ACL and the full integration matrix still
have reproducible blockers. Do not add features. Record SHA, logs, fixes, and
remaining problems under `docs/plan/tasks/21`, then provide commit links so the
next release step can be chosen from verified results.

## Friday, 30 October 2026

Week 1 of 6: finish the initial MVP audit. Goal: `v0.1.0`.

The original message reported reading Task 21, with `main` still at `8812bdc`.
It noted two passing archive-permission integration tests but no confirmed
complete green `make test-all` on the current revision. It did not claim fresh
test execution. Focus: localize two failures, rather than run another full build.

### 1. Check SSH configuration push

```sh
make test MODULE=tests/integration-test \
  TEST='GitSshTransportEndToEndIT#directConfigurationPushRetainsValidStateUntilAValidCommitArrives'
```

The current Failsafe goal correction above also applies to this historical
command. The previous mismatch was expected `invalid`, observed `unavailable`.

**Ready when:** the test passes, or a reproducible failure explains which
contract is violated: configuration validation, state application, or API reply.

### 2. Investigate the S3 metadata test

Check
`S3NativeGitRepositoryProviderIT.listsAllPagesAndRejectsCorruptOrMisplacedMetadata`,
which previously accepted corrupt metadata.

**Ready when:** determine whether the cause is integrity validation or the test
environment. Separately record whether S3-backed Git is mandatory for `v0.1.0`.
If it is excluded, do not expand the release merely to add S3, and do not hide
the failing tests.

### 3. Record Task 21 results

Record SHA, each test's outcome, the cause found, and the next step. A small fix
may be a separate commit; otherwise retain a concrete task with acceptance.

**Ready when:** GitHub evidence explains release state without oral context.

**Day success:** at least one of the two blockers is closed or localized enough
to fix without further investigation. Record progress and test/commit links
under `docs/plan/tasks/21`, preparing week 2 stabilization around the remaining
concrete problems.

## Saturday, 31 October 2026

Week 1 of 6: finish the MVP audit. Goal: `v0.1.0`.

The original message reported `8812bdc` from 29 October as the latest commit,
fixing executable permissions in the distribution. Search had not found new
commits for 30 October. Task 21 had successful focused checks, but no complete
release-gate confirmation on one final SHA. The author did not run tests.

Focus: finish the first week with two bounded tasks and no new features.

### 1. Confirm packaging on an identified SHA

The original commands were:

```sh
git rev-parse HEAD
git status --short
make test-all MODULE=core/bootstrap \
  TEST=DistributionArchiveIT MAVEN='mvn -Pdist'
```

These are historical source commands. When executing, preserve the intended
distribution profile and verify the effective profiles: the current Make goal
also supplies `-Pdev`.

**Ready when:** both archive tests pass, no checks are skipped, and results are
tied to a SHA. In particular, the extracted `bin/orion --help` must run without
`chmod`.

### 2. Prepare the week 2 blocker shortlist

Record the status of four known areas in Task 21: SSH configuration push
(`invalid` versus `unavailable`), S3 metadata integrity, npm security findings,
and the missing confirmed complete green `make test-all`.

For each, distinguish a confirmed defect from an unverified concern, identify
the next step, and state whether it blocks `v0.1.0`.

**Ready when:** at most three immediate fixes form a prioritized queue, and
S3's inclusion in the public MVP is decided separately.

**Day result:** a verified distribution on an exact SHA and an ordered list
for the following week. Record SHA, test results, blockers, and commit links
under `docs/plan/tasks/21`. Week 2 begins Monday, 2 November, with MVP
stabilization: prioritize a reliable minimal Git server and test real demand
through PULL rather than expand the product with new features.
