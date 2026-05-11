# Licensing Model Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Orion's BSL-to-AGPL licensing documents, contribution rules, CLA, trademark policy, and Maven license metadata.

**Architecture:** Keep all legal-facing project documents in the repository root for visibility. Put only project-specific BSL parameters in `LICENSE` and reference the canonical BSL text instead of modifying it. Use `CONTRIBUTING.md` and `CLA.md` to define contribution acceptance and commercial relicensing rights.

**Tech Stack:** Markdown/plain text documentation, Maven POM metadata.

---

### Task 1: Add License Documents

**Files:**
- Create: `LICENSE`
- Create: `NOTICE.md`
- Create: `TRADEMARKS.md`

**Steps:**

1. Add BSL project parameters to `LICENSE`.
2. Add notices and canonical BSL reference to `NOTICE.md`.
3. Add trademark rules to `TRADEMARKS.md`.

### Task 2: Add Contribution Documents

**Files:**
- Create: `CONTRIBUTING.md`
- Create: `CLA.md`

**Steps:**

1. Document contribution requirements in `CONTRIBUTING.md`.
2. Add the Orion Contributor License Agreement in `CLA.md`.
3. Ensure contributors keep copyright but grant DETA PRO B.V. relicensing and
   commercial sublicensing rights.

### Task 3: Update Project Metadata and README

**Files:**
- Modify: `pom.xml`
- Modify: `README.md`

**Steps:**

1. Add root POM license metadata for Business Source License 1.1.
2. Add README sections for license, contribution, and commercial licensing.
3. Run `git diff --check`.
