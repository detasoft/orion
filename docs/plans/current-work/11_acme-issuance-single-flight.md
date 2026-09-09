# Bound Concurrent ACME Issuance

Status: in progress
Source: finding 7 in [the module review](../../../net/http-core/MODULE_REVIEW.md)

- [ ] Bound concurrent ACME certificate issuance.
  - Owner: codex, session http-acme-8b4d, started 2026-09-09 21:05 Europe/Amsterdam.

Add non-blocking single-flight admission to the existing ACME certificate
service so issuance waiting for HTTP-01 callbacks cannot exhaust Jetty workers.

## Scope

- Admit one issuance at a time and release admission after success or failure.
- Return `409 Conflict` with an explicit busy response for a concurrent admin
  issuance request.
- Preserve synchronous successful PEM delivery, durable certificate install,
  configured issuance policy, and HTTP-01 challenge cleanup.

## Completion Criteria

- A concurrent issuance request is rejected promptly without entering the
  issuer or occupying another waiting worker.
- Admission becomes available again after both successful and failed issuance.
- Tests cover service concurrency, HTTP busy translation, and HTTP-01 callback
  capacity while an admitted issuance waits.
