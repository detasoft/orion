# Dynamic Domain Allocation

## Goal

Add an Orion-owned way to allocate, track, and release DNS names for Orion
resources without manually editing DNS records for every new service or
project.

The first useful version should manage DNS records and certificate issuance for
configured zones. It should not assume that Orion already has a complete
application ingress or routing layer.

## Current State

Orion already has several pieces that can support this work:

- `integration/cloudflare-api` can list zones and create, update, list, and
  delete DNS records;
- HTTPS transport has ACME configuration with configured domains and
  `allowRequestedDomains`;
- `AcmeCertificateService` can issue HTTP-01 certificates and persist an nginx
  PEM file;
- `AcmeHttpChallengeRoute` serves `/.well-known/acme-challenge/*`;
- `JettyHTTPServer` binds HTTP and HTTPS connectors from static configuration.

There is no domain allocation model, no persisted domain registry, no ownership
rules, no collision policy, no lifecycle for DNS records, and no link between a
domain name and a target Orion resource.

## Non-Goals

Do not build a full reverse proxy or application ingress in the first step.

Do not make Cloudflare the only long-term DNS provider. It can be the first
adapter, but Orion should own a small provider interface.

Do not automatically expose local-only transports to the public internet.

Do not issue wildcard certificates until the DNS-01 challenge path is designed.
The existing ACME code is HTTP-01 oriented.

Do not store DNS provider secrets in repository files without a broader secret
management plan.

## Scope

Introduce a domain allocation model with at least:

- allocation id;
- requested label;
- allocated FQDN;
- base zone;
- owner or requesting principal;
- target type and target id;
- DNS provider;
- provider record id;
- record type, content, TTL, and proxied flag where supported;
- status, timestamps, and failure reason.

Define a naming policy before implementing provider calls. The policy should
cover allowed labels, reserved names, maximum length, case normalization,
collision handling, and whether names are human requested or generated.

Add a provider abstraction for DNS changes. Cloudflare should be the first
provider adapter because the client already exists, but the service should be
testable with an in-memory provider.

Treat certificate issuance as a second step after DNS ownership is persisted.
The allocation service can request ACME certificates for allocated names only
when the name is inside an allowed configured zone.

## Phased Plan

Phase 1: Domain model and policy.

Create the domain allocation value objects and validation rules. Add tests for
normalization, invalid labels, reserved labels, duplicate allocations, and zone
matching.

Phase 2: Persistence boundary.

Add a small storage abstraction for domain allocations. Start with a local file
or repository-backed implementation only if the existing storage pattern is a
good fit. The model must be reloadable and must preserve provider record ids.

Phase 3: DNS provider abstraction.

Define operations for find zone, create record, update record, delete record,
and read record. Add a Cloudflare implementation around the existing
`CloudflareClient` and an in-memory fake for tests.

Phase 4: Allocation service.

Implement allocate, read, list, and release operations. Allocation should write
the local state first as a pending record, apply DNS, then mark the allocation
active. Release should remove DNS and keep enough tombstone data for audit or
debugging.

Phase 5: Admin API.

Add admin HTTP endpoints for allocation and release. Use application admin
authorization initially; later this can become a domain-specific grant.

Phase 6: Certificate integration.

Allow ACME issuance only for allocated domains. Decide whether certificates are
issued on demand, during allocation, or by a renewal job. Do not make DNS record
creation depend on certificate issuance succeeding unless the allocation policy
explicitly requires TLS readiness.

## Open Questions

Which Orion resource gets a dynamic domain first: repository HTTP access,
admin UI, future applications, build previews, or external service endpoints?

Should an allocation point to an IP address, CNAME target, Orion route, or a
future ingress backend?

Should allocation names be user-requested, generated from repository names, or
both?

Where should provider credentials live before a secret store exists?

Should released names be reusable immediately, delayed, or never reused?

Should certificates be one certificate per name, one certificate per allocation
group, or a shared SAN certificate per zone?

## Verification

Cover at least these cases:

- invalid labels and reserved labels are rejected before provider calls;
- duplicate allocation requests are deterministic and do not create duplicate
  DNS records;
- a successful allocation persists local state and provider record id;
- provider failure leaves a pending or failed state that can be retried or
  released;
- release deletes the provider record and updates local allocation state;
- Cloudflare adapter sends expected DNS record create, update, list, and delete
  requests through a mock HTTP server;
- ACME issuance refuses domains that are not allocated or not allowed by
  configuration;
- application admin authorization is required for allocation and release.
