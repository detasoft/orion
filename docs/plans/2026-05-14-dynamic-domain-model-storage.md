# Dynamic Domain Model and Storage

## Goal

Define and persist Orion-owned domain allocations before adding DNS provider
automation, certificate issuance, or routing behavior.

This phase should answer: what domain names Orion owns, who requested them,
what resource they point to, what DNS state is expected, and how that state
survives restart.

## Current State

The broader dynamic-domain plan identifies existing building blocks:

- `integration/cloudflare-api` can manage DNS records;
- HTTPS ACME config already has allowed domains and requested domain controls;
- `AcmeCertificateService` can issue HTTP-01 certificates;
- `JettyHTTPServer` serves HTTP and HTTPS from static transport config.

What is missing is the core domain registry:

- no allocation id;
- no canonical domain-name type;
- no zone policy;
- no target resource model;
- no persisted DNS desired state;
- no allocation lifecycle;
- no collision or reuse rules;
- no reload behavior.

## Non-Goals

Do not call Cloudflare or any other DNS provider in this phase.

Do not issue ACME certificates in this phase.

Do not add HTTP admin routes in this phase.

Do not implement ingress, reverse proxying, or per-domain routing.

Do not decide public exposure for native Git, SSH, HTTP Git, or admin routes.

Do not store DNS provider credentials.

## Domain Concepts

Add small domain objects before storage:

- `DomainName`: normalized fully qualified domain name without a trailing dot;
- `DomainLabel`: normalized single DNS label requested by a user or generated
  by Orion;
- `DomainZone`: configured base zone such as `example.test`;
- `DomainAllocationId`: stable generated id;
- `DomainTarget`: typed target for a future resource;
- `DomainDnsRecord`: expected DNS record shape;
- `DomainAllocation`: persisted aggregate.

The model should be independent from Cloudflare classes. Provider-specific ids
can be represented as opaque strings on the allocation record.

## Allocation Record

Each allocation should store:

- `id`;
- `requestedLabel`, nullable for generated names;
- `allocatedDomain`;
- `zone`;
- `ownerUserId`, nullable for system allocations;
- `targetType`;
- `targetId`;
- `recordType`;
- `recordContent`;
- `ttlSeconds`;
- `provider`;
- `providerZoneId`, nullable until DNS provider integration;
- `providerRecordId`, nullable until DNS provider integration;
- `status`;
- `createdAtEpochSecond`;
- `updatedAtEpochSecond`;
- `activatedAtEpochSecond`, nullable;
- `releasedAtEpochSecond`, nullable;
- `lastFailure`, nullable;
- `description`, nullable.

Start with a limited `targetType` enum:

- `APPLICATION`;
- `REPOSITORY_HTTP`;
- `ADMIN_HTTP`;
- `BUILD_PREVIEW`;
- `EXTERNAL_ENDPOINT`;
- `UNKNOWN`.

The first implementation can store targets without resolving them. Validation
should still reject blank target ids for target types that require one.

## Status Model

Use explicit allocation statuses:

- `RESERVED`: name is stored locally but DNS has not been created;
- `ACTIVE`: DNS is expected to exist and the allocation is usable;
- `FAILED`: DNS or follow-up activation failed, but the name remains reserved;
- `RELEASING`: release was requested and external cleanup is pending;
- `RELEASED`: allocation is no longer active;
- `TOMBSTONED`: historical record retained to prevent immediate reuse.

For this model/storage phase, transitions can happen without provider calls:

- new allocation starts as `RESERVED`;
- a test helper or future service may mark it `ACTIVE`;
- failed activation records `FAILED` and `lastFailure`;
- release marks `RELEASED` or `TOMBSTONED` according to reuse policy.

## Naming Policy

Create a `DomainAllocationPolicy` before storage writes.

Rules:

- lowercase all labels and FQDNs;
- reject empty labels;
- reject labels longer than 63 characters;
- reject total domain names longer than 253 characters;
- allow only ASCII letters, digits, and hyphens in user-requested labels;
- reject labels that start or end with a hyphen;
- reject path-like input, whitespace, wildcards, underscores, and trailing dots
  from user input;
- require allocated domains to be inside a configured zone;
- reserve system labels such as `admin`, `api`, `www`, `git`, `ssh`,
  `acme-challenge`, and `localhost` unless configuration allows them.

Generated labels should include enough entropy to avoid collisions, for
example `<prefix>-<short-random-id>`, and should still pass the same validation.

## Storage Boundary

Add a `DomainAllocationStorage` abstraction:

- `findById(DomainAllocationId id)`;
- `findByDomain(DomainName domain)`;
- `listActive()`;
- `listByTarget(DomainTarget target)`;
- `save(DomainAllocation allocation)`;
- `updateStatus(DomainAllocationId id, DomainAllocationStatus status, String failure)`;
- `release(DomainAllocationId id, Instant releasedAt)`;
- `loadSnapshot()`.

The abstraction should make uniqueness explicit:

- only one non-released allocation may own an `allocatedDomain`;
- only one active allocation may own the same provider record id;
- target uniqueness is policy-dependent and should not be hardcoded in storage.

## Local File Storage

Use local file storage first, under the Orion base directory:

```text
<baseDir>/domains/domain-allocations.json
```

Use a versioned JSON document:

```json
{
  "schemaVersion": 1,
  "allocations": []
}
```

Write atomically through a temporary file and rename. Read should validate the
whole document before replacing the in-memory snapshot.

The local implementation should keep a small in-memory index by id and domain
after loading. Saves should update the file and in-memory snapshot together.

## Repository-Backed Storage Follow-Up

A later implementation may store domain allocations in a configuration Git
repository, but local file storage is the first target.

Repository-backed storage should be considered only after deciding whether
domain allocations are operational state or desired configuration. DNS provider
record ids and failure state make this more operational than static config.

## Service Boundary

Add a `DomainAllocationService` above storage.

Responsibilities:

- validate requested labels and zones;
- generate ids and generated labels;
- enforce uniqueness;
- create reserved allocations;
- read and list allocations;
- release allocations;
- expose explicit state transitions for future DNS provider code;
- keep provider integration out of model tests.

The service should accept a clock for deterministic tests.

## Configuration

Introduce configuration for domain allocation separately from ACME:

- enabled flag;
- allowed zones;
- default zone;
- reserved labels;
- generated label prefix;
- tombstone retention policy;
- default DNS record type;
- default TTL.

Do not reuse `transport.https.acme.domains` as the domain allocation source of
truth. ACME allowed domains and Orion dynamic allocation zones overlap, but they
are not the same concept.

## Open Questions

Should released names become reusable immediately, after a retention period, or
never?

Should target uniqueness be enforced for some target types, for example one
domain per build preview but many domains per application?

Should `www` be reserved by default or available as a normal allocation?

Should allocation storage fail startup if the JSON document is invalid?

Should generated labels be random only, target-derived, or target-derived plus
random suffix?

Should domain allocation config live under `transport`, `domains`, or another
top-level configuration section?

## Verification

Cover at least these cases:

- valid labels normalize to lowercase;
- invalid labels are rejected before storage writes;
- labels outside allowed zones are rejected;
- reserved labels are rejected by default;
- generated labels pass validation and avoid collisions;
- duplicate active domain allocation is rejected;
- released or tombstoned allocation follows the selected reuse policy;
- records can be saved, loaded, and indexed by id and domain;
- malformed storage JSON fails clearly without partial state;
- atomic write leaves either the old valid file or the new valid file;
- list active excludes released records;
- status updates preserve provider ids and timestamps;
- service tests use a fixed clock for deterministic timestamps.
