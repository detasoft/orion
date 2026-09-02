# Module Review: `bom`

Date: 2026-09-02  
Status: reviewed in isolation

## Scope and coverage

This review covers only [`pom.xml`](pom.xml) and the module-specific change history. It deliberately does not
evaluate consumers, dependency direction, or consistency with other project modules.

The review uses these clarified requirements:

- the BOM is used only inside the project build;
- every produced project artifact belongs in the BOM;
- test artifacts belong in the BOM when multiple modules use them.

No source code, runtime flow, mutable state, persistence, concurrency, or lifecycle exists in this module.

## Current conceptual model

The module is an internal Maven BOM with two responsibilities:

1. Assign the current project version to produced Orion artifacts.
2. Keep explicitly selected Netty artifacts on one version.

All Orion entries use `${project.version}`. The Netty entries use the shared `${netty.version}` property.

## Highest-value findings

No confirmed architectural inconsistencies were found within the stated module boundary.

### Considered: production and test artifacts share one BOM

This is consistent with the module's requirements. The BOM is internal, every produced artifact is expected to
be listed, and shared test artifacts require centralized version management. Splitting them into a second BOM
would add a boundary without a demonstrated requirement.

### Considered: selected Netty artifacts are managed explicitly

Importing the upstream Netty BOM could replace the individual entries, but it would also manage artifacts not
currently named by this module. Without evidence from consumers, the broader version contract is not clearly
simpler than the current targeted list. This is therefore not a finding.

## Things to try deleting

None within the isolated scope.

## Proposed conceptual model

Keep the current model: one internal catalog of all produced Orion artifacts plus narrowly selected external
version constraints required by the build.

## Incremental migration path

No migration is justified by the isolated review.

## Do not change

- Keep a single version source for Orion artifacts through `${project.version}`.
- Keep shared test artifacts in this BOM when they are consumed by multiple modules.
- Keep `packaging` set to `pom`.
- Do not introduce a separate test BOM without an independent versioning or consumption requirement.

## Deferred questions

The following checks require a later cross-module review and are intentionally deferred:

- whether every produced artifact is present exactly once;
- whether every listed test artifact is shared by multiple modules;
- whether the selected Netty constraints cover all Netty artifacts used by the build.
