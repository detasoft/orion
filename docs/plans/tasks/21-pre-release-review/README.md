# Task 21 — Initial release-readiness audit and v0.1.0 plan

Status: Open  
Priority: P0 (release planning)  
Scope: First public Orion developer-preview release  
Audit date: 2026-10-25  
Evidence: repository default branch `main`; documentation and build manifests reviewed.  
Verification level: **static, partial**. No source checkout, build, integration test, deployment, or vulnerability scan was run. Items marked **VERIFY** are not confirmed defects.

## Goal

Publish a reproducible, supportable first public Orion release without expanding the scope to unfinished agentic/VCS architecture. Use this checklist as the release gate and turn confirmed failures into focused follow-up tasks.

## Observed baseline

- `README.md` documents SSH, HTTP, HTTPS and native Git transports, ACLs, admin API, bootstrap, key enrollment, and UI development.
- `Makefile` exposes `dist`, `test`, `test-all`, and `integration-test`.
- `pom.xml` is a multi-module Maven build with JDK 21 and modules including `agent`, `core`, `connectors`, `integration`, `tests`, `net`, and `git`.
- `DEVELOPMENT.md` documents Node 22.22.2, Rust 1.97.0, local server/agent/UI, and external integration-test prerequisites.
- `LICENSE` defines BUSL-1.1 terms and a version-relative change date; `RELEASES.md` currently records `1.0-SNAPSHOT` as unreleased.
- `THIRD_PARTY_NOTICES.md` says the exact release should include a dependency license report or SBOM.
- `docs/key-material-backup-and-restore.md` explicitly notes there is no cross-store atomic commit and requires consistent backup generations.

## Tasks and acceptance criteria

### 21.1 — Establish reproducible build and test baseline [P0, VERIFY]

- [ ] Record the exact commit SHA and supported host environment.
- [ ] From a clean checkout, run `make test` and `make test-all`; attach commands, logs, duration, failures, and any skipped tests.
- [ ] Run frontend `npm ci`, `npm test`, and `npm run build` in `net/frontend/ui`.
- [ ] Run `make dist`; verify a fresh distribution starts without developer-only state.
- [ ] Inventory CI workflows and status checks; if missing, add a minimum reproducible release gate.

**Done when:** results are reproducible on a clean environment, and failures have dedicated actionable issues. **No CI defect is asserted until workflows/statuses are inspected.**

### 21.2 — End-to-end Git and authorization smoke matrix [P0, VERIFY]

- [ ] Create repository, clone, fetch, push, and reject unauthorized push via SSH.
- [ ] Repeat supported operations over HTTP(S), including invalid/expired credentials.
- [ ] Verify admin API repository/user/token/ACL operations, denial paths, and privilege boundaries.
- [ ] Check concurrency, restart persistence, and behavior for failed/incomplete pushes.
- [ ] Record tests and expected outcomes in a release smoke-test document.

**Done when:** every publicly advertised core transport and security boundary has passing evidence or a documented preview limitation.

### 21.3 — Installation, configuration, and recovery drill [P0, VERIFY]

- [ ] Install from a packaged artifact on a clean host using only public instructions.
- [ ] Confirm first-start password/key enrollment and normal subsequent restart.
- [ ] Exercise backup/restore of repositories, versioned ACL/configuration, and protected key material as a consistent generation.
- [ ] Test failure/recovery when a required material store is missing; ensure no accidental regeneration.
- [ ] Document upgrade, rollback, persistent paths, service management, and supported deployment environments.

**Done when:** a new operator can install, restart, back up, and restore without undocumented development steps.

### 21.4 — Release security and dependency review [P0, VERIFY]

- [ ] Review network listener defaults, HTTP exposure, TLS/ACME setup, secret logging, token lifetime/revocation, and root recovery paths.
- [ ] Run dependency vulnerability and license checks for Maven, npm, and Rust components; triage actual findings.
- [ ] Verify no private keys, passwords, tokens, or generated local state are included in distribution archives.
- [ ] Document security reporting contact and support expectations for developer preview.

**Done when:** critical/high findings are fixed or explicitly accepted with rationale and safe deployment constraints. **No vulnerability is claimed by this audit.**

### 21.5 — Versioning, license, and release artifacts [P0, CONFIRMED DOCUMENTATION GAP]

- [ ] Choose the first public version (e.g. `0.1.0`) and align Maven/UI/distribution versioning; current `RELEASES.md` still lists only `1.0-SNAPSHOT`.
- [ ] At publication, add actual first public UTC distribution date and calculated four-year change date to `RELEASES.md`, consistent with `LICENSE`.
- [ ] Confirm archives include `LICENSE`, `LICENSE-BUSL-1.1.txt`, `LICENSE-AGPL-3.0-or-later.txt`, `NOTICE.md`, `THIRD_PARTY_NOTICES.md`, and `TRADEMARKS.md`.
- [ ] Generate and attach an exact-version dependency license report or SBOM as required by `RELEASES.md`.
- [ ] Publish checksums, release notes, known limitations, and a tested download/installation path.

**Done when:** published artifacts and legal/version metadata correspond to the exact release commit.

### 21.6 — Preview documentation and first external validation [P1, VERIFY]

- [ ] Produce a 10–15-minute quick start using the distributed artifact, not a source checkout.
- [ ] Distinguish production-supported features from experimental agent/session functionality.
- [ ] Document operational requirements, data durability boundaries, backup responsibilities, and known limitations.
- [ ] Ask one external tester to install and run the smoke matrix; record feedback and blockers.
- [ ] Define DetaSoft managed deployment/support contact and the next feedback milestone.

**Done when:** at least one fresh external installation succeeds and feedback is captured.

## Release gate

Release only when 21.1–21.5 are complete, with no untriaged critical security/data-loss defects. Task 21.6 may finish alongside the developer-preview launch. Defer new features and architectural redesigns to later milestones unless a demonstrated blocker requires them.

## Audit limitations / next inspection

This is a **first-pass task inventory, not a completed code/security audit**. GitHub file-content access was available, but directory enumeration and a runnable checkout were not available in this pass. The absence of `.github/workflows/ci.yml` or `build.yml` at those specific paths does **not** prove absence of CI; enumerate actual workflows before filing a defect. Review the existing task-plan convention and migrate/rename this document if `docs/plan/tasks/21` uses another schema.
