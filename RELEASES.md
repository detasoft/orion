# Orion Release License Manifest

This file records the first public distribution date for each Orion release.
The date is part of the Business Source License change-date calculation in
`LICENSE`.

| Version | First public distribution | Change license | Change date |
| --- | --- | --- | --- |
| 1.0-SNAPSHOT | Unreleased | AGPL-3.0-or-later | Not started |

## Release Procedure

When publishing a new public version:

1. Replace the snapshot row or add a new row for the exact release version.
2. Record the UTC calendar date when that version first becomes publicly
   available.
3. Set the change date to the fourth anniversary of that public distribution
   date.
4. Keep the project-specific BSL parameters in `LICENSE` in sync with this
   manifest.
5. Include `LICENSE`, `LICENSE-BUSL-1.1.txt`,
   `LICENSE-AGPL-3.0-or-later.txt`, `NOTICE.md`, `THIRD_PARTY_NOTICES.md`,
   and `TRADEMARKS.md` in release artifacts.
6. Publish or attach the dependency license report or SBOM generated for the
   release build.
