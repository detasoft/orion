# Review Preview Security and Dependencies

Status: todo

## Requirements

Assess the release candidate's exposure, secret handling, authentication
lifecycle, dependency vulnerabilities, and license obligations. Establish an
explicit security-reporting contact and preview support boundary.

## Design Decisions

- Distinguish scanner findings from verified reachability and exploitability.
  Development tooling findings are not automatically production vulnerabilities.
- Inventory the actual Maven, npm, and Rust dependencies in the candidate and
  packaged artifact. Do not infer completeness from a filename-only archive scan.
- Fix demonstrated defects with focused changes; do not upgrade unrelated
  dependencies or change legal terms as part of an unqualified bulk repair.

## Implementation Plan

1. Review listener defaults, HTTP exposure, TLS/ACME setup, secret logging,
   token expiry/revocation, and privileged recovery paths and verify their
   positive and denial/failure scenarios.
2. Run current vulnerability and license checks for Maven, npm, and Rust.
   Reassess the 28 October npm findings affecting Vite, Vitest/@vitest/mocker,
   brace-expansion, and source-map-js; the historical scanner output alone does
   not establish current affected versions or production reachability.
3. Triage each finding with its affected dependency, reachable use, severity,
   remediation or mitigation, and verification. Obtain a release-owner decision
   for any remaining accepted critical/high finding.
4. Inspect the actual distribution for private keys, credentials, tokens, and
   generated local state; distinguish public certificates from private material.
5. Agree and document the security-reporting channel and support expectations.
   Existing commercial/legal contacts do not by themselves establish a security
   response commitment or SLA.

## Dependencies

Use the candidate inventory from `01_reproducible-build.md` and a packaged
artifact from `03_installation-and-recovery.md` for the final content review.
Source and dependency triage can begin before artifact verification finishes.
Release-owner input is required for accepted risks and contact commitments.

## Acceptance Criteria

- Every scanner finding is triaged; critical/high findings are fixed or have
  an explicit accepted rationale and safe deployment constraints.
- Advertised security boundaries have evidence, and the archive contains no
  unintended secrets or generated developer state.
- Dependency/license inventory and security/support documentation describe the
  exact candidate and do not overstate the scope of verification.
