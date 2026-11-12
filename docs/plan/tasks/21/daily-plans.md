# Orion v0.1.0 — Daily Release Plans Archive

> Planning archive only. All assignments below are **uncompleted by default**; this file does not report execution or test results. Actual release evidence belongs in `docs/plan/tasks/21/release-gates.md`, which this archive must not modify.
>
> **Source fidelity:** Plans for **6–12 November 2026** are preserved from the conversation text available in this session, translated into English. The earlier **26 October–5 November** entries and the original roadmap are reconstructed from available conversation history and retained summaries; the complete verbatim text of those earlier messages was not available. Accordingly, those entries must not be treated as word-for-word transcripts or as proof that an unquoted command appeared in the original. Repeated assignments are deliberately retained. Historical statements about repository state are planning context, not fresh verification.

## Original six-week roadmap (separate from daily assignments)

**Period:** 26 October–6 December 2026. **Outcome:** first public Orion Git-server release, `v0.1.0`. The original roadmap was described as a six-stage, 24-task checklist; the exact wording of all 24 original checklist entries is not recoverable from the available history, so this section preserves the known scope and stages without inventing a verbatim checklist.

**Release MVP:** Git clone/fetch/push, repository creation, basic authentication, repository access control, reproducible installation/update, persistent data, and backup/recovery. Do not expand the release with agentic workflows, immutable changes, advanced conflict management, elaborate UI, enterprise capabilities, or integrations merely to fill the roadmap.

**Prioritization:** use Rob Snyder's PULL questions: **Project** (what job is the user doing?), **Unavoidable** (why solve it now?), **List of options** (what else is available?), and **Limitations** (why alternatives are insufficient?). Validate actual user demand rather than assuming feature desirability.

| Week | Dates | Intended milestone |
| --- | --- | --- |
| 1 | Oct 26–Nov 1 | Audit the MVP; fix release scope; establish build/test baseline and initial blocking issues. |
| 2 | Nov 2–8 | Stabilize the MVP; investigate SSH configuration-push and S3 metadata regressions; test backup/restore and Git CLI; define release gates. |
| 3 | Nov 9–15 | Validate changes after refactoring; produce reproducible distribution and installation; exercise real Git client and ACL scenarios. |
| 4 | Nov 16–22 | Close release-critical defects; verify security, operational documentation, and supported installation paths. |
| 5 | Nov 23–29 | Prepare and test a release candidate, including versioning, licensing, artifacts, and an external-user installation walkthrough. |
| 6 | Nov 30–Dec 6 | Resolve release-candidate blockers, publish v0.1.0 with truthful scope and documentation, and gather initial user feedback. |

The later-week milestones are roadmap intent, **not** claims that these activities occurred.

## Daily plans — chronological record

### Monday, 26 October 2026 — Week 1: MVP scope and baseline

1. **Fix the v0.1.0 scope using PULL.** Write down the minimal user job and the must-work Git hosting scenarios; separate required clone/fetch/push, repository creation, authentication, ACL, installation, and persistence from deferred agentic and enterprise ideas. **Ready when:** the MVP boundary and exclusions are written down and can be used to reject nonessential work.
2. **Record a reproducible test baseline.** Capture the checkout SHA and run the baseline test goal:
   ```sh
   git rev-parse HEAD
   make test
   ```
   **Ready when:** the SHA, actual exit status, log or report, and any failing tests are recorded; do not mark unrun tests as passing.
3. **Establish distribution status.**
   ```sh
   make dist
   ```
   Inspect the produced artifact and record what it contains, or the first reproducible failure. **Ready when:** the distribution result is known on the recorded SHA.

**Progress record:** log the SHA, command results, links, and blockers under Task 21; report what was actually done.

### Tuesday, 27 October 2026 — Week 1: Git and ACL baseline

1. **Reproduce the ordinary Git workflow.** Check SSH clone, commit, push, and subsequent fetch/clone with a real Git client or the existing end-to-end test suite. **Ready when:** the pushed commit can be read back and compared by SHA, or a precise failing operation is documented.
2. **Check authorization boundaries.** Verify a read-only user cannot push while an authorized writer can. **Ready when:** both positive and negative cases have recorded outcomes; a rejected push must be an authorization rejection rather than an unrelated connection failure.
3. **Record the baseline and prioritize blockers.** Capture `git rev-parse HEAD`, applicable `make test` results, and the top release risks. **Ready when:** the next repair has a testable acceptance condition.

**Progress record:** record commands used, results, and evidence. This early entry is reconstructed from conversation history; exact original shell invocations beyond those shown are unavailable.

### Wednesday, 28 October 2026 — Week 1: integration failures

1. **Investigate SSH configuration-push regression.** Reproduce `GitSshTransportEndToEndIT.directConfigurationPushRetainsValidStateUntilAValidCommitArrives`, including the unexpected `unavailable` versus expected `invalid` state. **Ready when:** the behavior is fixed with regression coverage or the root cause and a minimal reproduction are recorded.
2. **Investigate S3 metadata integrity.** Reproduce `S3NativeGitRepositoryProviderIT.listsAllPagesAndRejectsCorruptOrMisplacedMetadata`. **Ready when:** the failure is localized and either corrected or explicitly tracked with evidence.
3. **Capture the broad test state.** Run or review `make test-all` and distinguish confirmed passing suites from incomplete or failing ones. **Ready when:** the report identifies the SHA, failing tests, and whether the complete suite actually finished.

**Progress record:** append evidence to Task 21; do not convert partial runs into an overall PASS.

### Thursday, 29 October 2026 — Week 1: distribution and release metadata

1. **Check distribution executable permissions.** Build the archive and verify the launcher and bundled runtime remain executable after extraction, without manual `chmod`. **Ready when:** an extracted distribution launches or the archive defect is reproducible.
2. **Review version and licensing metadata.** Compare Maven version, UI version, `RELEASES.md`, license files, and the intended `v0.1.0` name. **Ready when:** discrepancies and required pre-publication decisions are documented; do not invent a first-public-release date.
3. **Repeat targeted Git/ACL verification after any packaging fix.** Use `make test` or focused project test goals where applicable. **Ready when:** outcomes and tested SHA are recorded separately from the packaging result.

**Progress record:** attach the commit and artifact information; retain any unverified release items.

### Friday, 30 October 2026 — Week 1: stabilize the critical path

1. **Recheck the SSH configuration-push failure.** Verify that an invalid pushed configuration does not replace the last valid active state. **Ready when:** a regression test passes on a recorded SHA or a specific defect remains open.
2. **Recheck S3 metadata failure and release relevance.** Identify whether the failing storage mode is in the intended v0.1.0 support scope. **Ready when:** the failure and the scope decision are recorded independently.
3. **Refresh test and distribution baseline.** Use `make test` and `make dist` as applicable; capture real outcomes rather than relying on earlier reports. **Ready when:** there is a concise current blocker list.

**Progress record:** include command results and any repair links. This entry is reconstructed; exact original command flags are not recoverable.

### Saturday, 31 October 2026 — Week 1: release-readiness gaps

1. **Check packaging from a clean extraction.** Validate the built distribution rather than only development startup. **Ready when:** startup succeeds without files from the checkout or a reproducible failure is filed.
2. **Check SSH/ACL smoke coverage.** Confirm authorized push, clone/fetch, and denied unauthorized push. **Ready when:** each outcome has a command or test reference.
3. **Review release-critical documentation and security gaps.** Include version/license alignment and dependency findings; keep the P0 list small. **Ready when:** each gap has a concrete acceptance condition.

**Progress record:** note SHA and evidence. This entry is reconstructed from the early-plan themes, not a verbatim transcript.

### Sunday, 1 November 2026 — Week 1 closeout

1. **Consolidate the MVP audit.** Distinguish verified PASS, FAIL, and NOT TESTED across build, SSH, ACL, S3, distribution, and data durability. **Ready when:** no claim of a green release is based on missing evidence.
2. **Select the next critical regression.** Prioritize the SSH configuration-push and S3 metadata issues rather than adding functionality. **Ready when:** the selected issue has a reproducible failing test and expected result.
3. **Prepare Week 2 stabilization work.** Identify the smallest set of blockers preventing a testable external installation. **Ready when:** the next actions are specific and bounded.

**Progress record:** save evidence and remaining issues in Task 21. This entry is reconstructed from available summaries.

### Monday, 2 November 2026 — Week 2: regressions and test reproducibility

1. **Run the relevant SSH configuration-push regression.** Investigate the `invalid`/`unavailable` mismatch and preservation of last valid state. **Ready when:** the focused test passes or a reproducible failure is captured.
2. **Run the S3 metadata integrity regression.** Check pagination and rejection of corrupt/misplaced metadata. **Ready when:** the specific test outcome is recorded with SHA and logs.
3. **Establish a reliable broader test result.** Run `make test-all` if feasible and distinguish timeouts, environmental failures, and code defects. **Ready when:** all failures are classified rather than hidden.

**Progress record:** record actual tests executed and next steps. This entry is reconstructed; do not assume tests ran.

### Tuesday, 3 November 2026 — Week 2: targeted repairs

1. **Narrow the SSH configuration-push failure to its state transition.** Verify invalid pushes cannot discard the last valid configuration. **Ready when:** an executable regression proves the invariant or isolates the defect.
2. **Narrow the S3 integrity issue.** Confirm behavior for multi-page listings and invalid metadata placement. **Ready when:** a regression outcome and affected scope are documented.
3. **Update Task 21 with the test baseline.** Preserve `make test` versus `make test-all` distinctions and the checked SHA. **Ready when:** the blocking issues have reproducible steps.

**Progress record:** provide commit/test links. This entry is reconstructed from retained context.

### Wednesday, 4 November 2026 — Week 2: recovery preparation

1. **Prepare an isolated backup/restore exercise.** Follow `docs/key-material-backup-and-restore.md`; preserve the matching key-material store, configuration, ACL, and repositories. **Ready when:** the required data and restore sequence are explicit.
2. **Avoid accidental key regeneration.** Do not use `--create-if-missing` to disguise missing key material during recovery. **Ready when:** the restore test can distinguish restored identity from a newly initialized installation.
3. **Record the remaining release blockers.** Include SSH, S3, distribution, and recovery evidence without marking unrun checks as passed. **Ready when:** Task 21 contains an actionable next step for each blocker.

**Progress record:** capture environment, SHA, and restore evidence. This entry is reconstructed from the transition to the Nov 5 backup/restore plan.

### Thursday, 5 November 2026 — Week 2: backup and restore

1. **Execute a backup/restore drill in an isolated installation.** Follow `docs/key-material-backup-and-restore.md`; restore matching key material, configuration, ACL, and repositories without `--create-if-missing`. **Ready when:** the restored service can access the original data and identity; otherwise record the exact failure.
2. **Make the recovery drill reproducible.** Write down commands, prerequisites, backup inputs, restore order, and validation checks. **Ready when:** another operator can repeat the drill without undocumented manual repair.
3. **Report the state of release blockers.** Preserve the SSH configuration-push, S3 integrity, and broad test concerns. **Ready when:** progress and unresolved items are documented against a SHA.

**Progress record:** attach the restore evidence and a short end-of-day status. This entry is reconstructed from the retained Nov 5 plan summary.

### Friday, 6 November 2026 — Week 2: close a blocker and summarize

Historical planning context: the conversation cited `8812bdc` as `main`, with an earlier report of 22/23 SSH E2E tests and a non-green `make test-all`. These are **claims made in the daily message**, not fresh checks for this archive.

1. **Close the SSH configuration-push blocker (45–60 minutes).** Focus on `directConfigurationPushRetainsValidStateUntilAValidCommitArrives`: why the state becomes `unavailable` rather than `invalid`, and whether the last valid configuration remains active. **Ready when:** the fix and regression test pass on a recorded SHA; alternatively, document a localized root cause and a concrete fix task rather than another general test run.
2. **Summarize yesterday's backup/restore drill (15 minutes).** Check for evidence of recovering repository, ACL, and key material in isolation. **Ready when:** a successful restore is linked or the restore blocker is recorded; a simple restart does not count.
3. **Close Week 2 (15 minutes).** In `docs/plan/tasks/21`, record SSH configuration, backup/restore, S3 integrity, and full release-gate status. Decide which issues block the local Git MVP and which are outside public scope. **Ready when:** at most three prioritized blockers have concrete next actions.

**Day success:** one fixed defect or proven recovery result plus an updated release-readiness picture. **Progress record:** commit SHA, PASS/FAIL, evidence links, fixed items, and remaining issues.

### Saturday, 7 November 2026 — Week 2: installed Git CLI and gates

Historical planning context: the daily message cited `8812bdc` and said no newer main commits or published GitHub Actions workflows were found. This is not revalidated here.

1. **Test the installed Orion with the system Git CLI (45–60 minutes).** From an isolated distribution installation, without manual `chmod`: create a repository and SSH push; clone with a second client and compare commit SHA; deny a read-only user's push. Earlier JGit E2E coverage does not substitute for this external-client check. **Ready when:** all three scenarios PASS, or there is a precise reproducible defect, with Orion SHA and logs.
2. **Create a single release-gates document (20 minutes).** Prepare `docs/plan/tasks/21/release-gates.md` with build, installation, SSH/ACL, configuration preservation, backup/restore, dependency security, version, and supported platforms. Use PASS/FAIL/NOT TESTED and evidence for each, select at most three P0 blockers, and explicitly decide whether S3 is in v0.1.0. **Ready when:** one commit records release readiness without requiring a reread of the whole audit.

**Day success:** a newly proven user scenario and clear criteria for Week 3. **Progress record:** save results under Task 21 and send a concise report.

### Sunday, 8 November 2026 — Week 2: scope and version alignment

Historical planning context: the daily message cited `8812bdc`, no release-gates file, Maven `1.0-SNAPSHOT`, and UI `0.1.0`.

1. **Fix release gates and scope.** Create `docs/plan/tasks/21/release-gates.md`. Define the minimum supported configuration (local storage, SSH clone/push/pull, ACL); decide whether S3 and HTTP(S) are publicly supported. For installation, Git CLI smoke, SSH configuration push, backup/restore, and `make test-all`, record PASS/FAIL/NOT TESTED, SHA, and evidence. **Ready when:** there are at most three P0 blockers and explicit publication conditions; untested scenarios are not called successful.
2. **Prepare version alignment in a separate branch.** Change `<revision>` in `bom/pom.xml` from `1.0-SNAPSHOT` to `0.1.0-SNAPSHOT`; check Maven version resolution and UI `0.1.0`:
   ```sh
   mvn -q -DforceStdout \
     help:evaluate -Dexpression=project.version
   ```
   Review `RELEASES.md` without inventing the first public distribution date, which affects BSL change date. **Ready when:** Maven resolves `0.1.0-SNAPSHOT` and the change is isolated in a commit; licensing dates remain truthful.

**Day success:** clear MVP boundaries and prepared versioning. **Progress record:** SHA, commands, remaining blockers, and commit links.

### Monday, 9 November 2026 — Week 3: regression checks after refactoring

Historical planning context: the daily message cited `785c4c6` and recent changes to `VcResource`, revisions, key material, and configuration; no CI result or direct test execution was available.

1. **Validate the test runner on macOS (15 minutes).** GNU `timeout` may be absent on standard macOS:
   ```sh
   command -v timeout || command -v gtimeout
   make test MODULE=core/bootstrap \
     TEST=KeyMaterialBootstrapTest \
     LOG=/tmp/orion-bootstrap-nov09.log
   ```
   **Ready when:** the runner works, the test actually executes, and the result is tied to a SHA. If `timeout` is missing, repair Makefile portability without dropping the timeout.
2. **Check SSH after `VcResource` refactoring (45–60 minutes).**
   ```sh
   make test-all MODULE=tests/integration-test \
     TEST=GitSshTransportEndToEndIT \
     LOG=/tmp/orion-ssh-nov09.log
   ```
   Focus on first configuration creation, preservation of valid state after invalid push, and ordinary clone/push. **Ready when:** the class passes completely or a specific regression, reproduction, and fix/task are linked; a full `make test-all` is not required for this assignment.
3. **Update release gates (15 minutes).** Create `docs/plan/tasks/21/release-gates.md` if absent. Record test results, checked SHA, and at most three P0 blockers; note Maven `1.0-SNAPSHOT` versus intended `0.1.0`. **Ready when:** every entry uses real PASS/FAIL/NOT TESTED evidence.

**Day success:** confirmed runner and one SSH E2E run after refactoring. **Progress record:** SHA, test results, repairs, remaining blockers.

### Tuesday, 10 November 2026 — Week 3: regressions and distribution

Historical planning context: the daily message cited `b7e0be7`, new lifecycle/S3 regression tests, frontend tests in Maven, and unavailable CI statuses.

1. **Run two regression checks (45–60 minutes)** on a clean checkout of the recorded SHA:
   ```sh
   make test MODULE=core/acl \
     TEST=OrionAccessControlServiceImplTest \
     LOG=/tmp/orion-acl-nov10.log

   make test-all MODULE=git/git-s3-storage \
     TEST=S3NativeGitRepositoryProviderIT \
     LOG=/tmp/orion-s3-nov10.log
   ```
   **Ready when:** both actually execute tests (not zero tests), pass, and save logs; otherwise record the exact failing test and reproduction. Recheck S3 because its previously blocking test changed.
2. **Build and check the distribution (45 minutes).**
   ```sh
   make dist
   ```
   Extract outside the checkout, run `bin/orion --help` without manual `chmod`, check bundled Java permissions, and verify frontend tests participate in the build. **Ready when:** BUILD SUCCESS, functional archive and help command, and recorded archive SHA-256; otherwise record the first blocker.
3. **Update release gates (10–15 minutes).** Create `docs/plan/tasks/21/release-gates.md` if missing. Record the two checks, SSH/ACL, backup/restore, and at most three P0 blockers; decide whether S3 is supported in public v0.1.0. **Ready when:** each item has PASS/FAIL/NOT TESTED, checked SHA, and evidence.

**Day success:** at least one confirmed check on current SHA and an updated blocker list. **Progress record:** actual outcomes, commit links, and outstanding problems.

### Wednesday, 11 November 2026 — Week 3: installed Git server

Historical planning context: the daily message cited `b7e0be7`, no published status checks, and missing release gates.

1. **Check the distribution on `arch-vm` (P0).**
   ```sh
   git rev-parse HEAD
   make dist
   sha256sum core/bootstrap/target/orion-dist.tar.gz
   ```
   Extract outside checkout and launch `bin/orion` without manually fixing permissions or checkout dependencies. **Ready when:** build succeeds, launcher works, archive SHA-256 and logs are saved; otherwise record the exact failure.
2. **Perform one real SSH Git smoke test (P0).** On the installed server: user A pushes a commit; user B clones and gets the same commit SHA; read-only user is denied push. **Ready when:** three documented outcomes with commands/logs or a reproducible defect. If task 1 fails, mark this scenario BLOCKED.
3. **Record v0.1.0 readiness (15 minutes).** Create `docs/plan/tasks/21/release-gates.md` if absent. Include today's results, SSH/ACL, backup/restore, at most three P0 blockers, and the Maven `1.0-SNAPSHOT` versus `0.1.0` discrepancy. Do not invent the public release date in `RELEASES.md`. **Ready when:** a committed document lists PASS/FAIL/NOT TESTED, checked SHA, and evidence for each item.

**Day success:** one reproducible distribution and recorded gates; Git CLI test if time permits. **Progress record:** commit links, results, and blockers.

### Thursday, 12 November 2026 — Week 3: token handling and installed checks

Historical planning context: the daily message cited `fc66887`, `make dist-image`, and an observed token print in `make/server.mk`. This archive does not independently confirm the defect or its resolution.

1. **Remove administrative-token output (15–20 minutes).** In `make/server.mk` remove `printf 'ORION_TOKEN=%s\n' "$token"` from `check-jetty-git` and `check-ssh-git` while retaining token-based authorization. **Ready when:** neither stdout nor diagnostic logs disclose the token; the change is committed separately.
2. **Check the real release archive (30–45 minutes)** on `arch-vm` or another Linux machine:
   ```sh
   git rev-parse HEAD
   make dist
   sha256sum core/bootstrap/target/orion-dist.tar.gz
   ```
   Extract outside checkout, run `orion/bin/orion --help`, and test first startup with an isolated data directory. **Ready when:** archive builds, launcher needs no `chmod`, bundled Java works, startup succeeds, and hash/log are saved.
3. **Test Git against the installed server (30 minutes)** after task 1:
   ```sh
   make check-git-all
   ```
   Additionally verify that read-only users cannot push. **Ready when:** push, clone, and HTTP Git discovery work; forbidden push is denied. For failures record the exact command and reproduction, not only a generic FAIL.

**Minimum day result:** remove token disclosure and prove the current distribution starts; defer task 3 if needed. **Progress record:** write actual PASS/FAIL/NOT TESTED, SHA, logs, and blockers to `docs/plan/tasks/21/release-gates.md` (a separate execution-results file, not changed by this archive).

## Archive maintenance rules

- Preserve each dated assignment, acceptance criterion, and command, including repetition across days.
- Do not mark any task completed based solely on these plans or on earlier assistant claims.
- Keep actual test/build/restore evidence and release-gate state in `release-gates.md`, not in this historical archive.
- When original messages for 27 October–5 November become available, replace the clearly labeled reconstructions with faithful English translations, preserving all details and repeated items.
