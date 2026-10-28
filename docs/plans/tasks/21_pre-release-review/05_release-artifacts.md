# Prepare and Publish Consistent Release Artifacts

Status: todo

## Requirements

Publish one release whose version, source revision, artifacts, checksums,
dependency inventory, notices, and public documentation agree.

## Design Decisions

- `0.1.0` is a proposed first-preview version, not an already approved decision.
  Align Maven, UI, and distribution metadata with the owner's selected version.
- Preserve the existing license terms. `RELEASES.md` must use the actual first
  public UTC distribution date and the corresponding four-year change date,
  not a planning or verification date.
- Generate notices and inventory from the final artifacts; a diagnostic package
  or an inventory from a different revision is insufficient.

## Implementation Plan

1. Confirm the version and preview scope with the release owner, update version
   metadata consistently, and identify the resulting candidate revision.
2. Confirm required checks against that candidate and final distribution after
   version changes; do not reuse verification of different artifact contents.
3. Include `LICENSE`, `LICENSE-BUSL-1.1.txt`, `LICENSE-AGPL-3.0-or-later.txt`,
   `NOTICE.md`, `THIRD_PARTY_NOTICES.md`, `TRADEMARKS.md`, and `RELEASES.md` in
   the release. Make their location clear to an operator using the archive.
4. Generate and attach an exact-version dependency license report or SBOM as
   required by `RELEASES.md`. Verify it corresponds to packaged dependencies.
5. Prepare checksums, release notes, known limitations, and download/install
   instructions. At publication, record the actual public distribution and
   change dates and verify the downloaded artifact against its checksum.

## Dependencies

Publication requires the accepted build, transport/auth, installation/recovery,
and security results in this subtree. Version selection and publication timing
depend on the release owner. External preview validation can proceed against
the identified candidate without waiting for public publication.

## Acceptance Criteria

- Public version/date metadata, notices, dependency report/SBOM, checksums, and
  artifacts all correspond to the exact released candidate.
- The public download and documented installation path are verified.
- Required verification and risk decisions are complete before publication;
  no diagnostic artifact is presented as the verified release.
