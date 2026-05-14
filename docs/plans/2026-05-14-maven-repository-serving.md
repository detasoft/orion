# Maven Repository Serving Over HTTP and HTTPS

## Goal

Serve Maven artifacts from Orion over the existing HTTP and HTTPS transports so
Maven, Gradle, and other repository clients can resolve and optionally publish
artifacts through standard repository paths.

The first useful version should support a hosted Maven repository, not a proxy
cache for Maven Central.

## Current State

Orion has an HTTP routing layer with `OrionHttpRoute`, route registration, an
authorization filter, admin routes, and Git smart HTTP routing under `/r/*`.

Repository access control exists for Git repositories, but there is no Maven
artifact resource model, no Maven route, no artifact storage abstraction, and no
Maven metadata writer.

The Git repository storage abstractions are built around Git operations and
should not be assumed to be the right storage model for binary Maven artifacts.

## Non-Goals

Do not implement a Maven Central proxy cache in the first version.

Do not implement search indexes, package browsing UI, or dependency graph
analysis first.

Do not store artifacts inside Git repositories unless there is a deliberate
reason. Maven artifacts are immutable binary blobs plus metadata, not source
history.

Do not support every repository manager feature before basic resolve and deploy
work.

## Scope

Add a hosted Maven repository route, for example under `/maven/*` or
`/api/maven/*`. The route should preserve Maven layout paths such as:

- `group/id/artifact/version/artifact-version.pom`;
- `group/id/artifact/version/artifact-version.jar`;
- `group/id/artifact/maven-metadata.xml`;
- checksum files such as `.sha1`, `.sha256`, and `.md5` if supported.

Define storage around artifact coordinates and raw paths:

- repository name;
- group id, artifact id, version, classifier, extension;
- snapshot or release version;
- content length, checksum values, created time, and uploader;
- metadata files and generated checksums.

Support read-only resolve first, then authenticated deploy. Releases should be
immutable by default. Snapshots may be overwritten or timestamped depending on
the metadata policy chosen for the repository.

## Phased Plan

Phase 1: Maven path parser and model.

Parse Maven repository paths into structured coordinates where possible while
still allowing raw metadata and checksum file requests. Add tests for regular
artifacts, classifiers, POM files, metadata, snapshots, and invalid paths.

Phase 2: Storage abstraction.

Introduce a Maven artifact storage interface that can read, write, list, and
delete raw repository paths with metadata. Start with local filesystem storage
under the Orion base directory.

Phase 3: Read-only HTTP route.

Add GET and HEAD support for artifact and metadata files. Return correct status
codes, content type where known, content length, cache headers, and not-found
responses. Keep authentication policy explicit: public read, token read, or
repository-grant read.

Phase 4: Deploy route.

Add PUT support for authenticated uploads. Define overwrite policy separately
for releases and snapshots. Generate or validate checksums and reject
incomplete uploads.

Phase 5: Metadata handling.

Generate and update `maven-metadata.xml` for snapshots and version listings.
Use a structured XML API rather than string concatenation. Define locking so
parallel deploys do not corrupt metadata.

Phase 6: Authorization model.

Add Maven-specific resources and grants or map hosted Maven repository access
to existing repository grants only if the semantics are truly the same. Deploy
requires write; resolve requires read.

## Open Questions

Should Maven repositories be named separately from Git repositories, or should
each Git repository optionally expose a Maven repository namespace?

Should unauthenticated reads be allowed for public artifacts?

Should releases be immutable by default, with admin-only delete or overwrite?

Should snapshot deployment use Maven timestamped snapshots, mutable
`-SNAPSHOT` files, or both?

Which checksums should be mandatory: SHA-1 for compatibility, SHA-256 for
modern integrity, or both?

Should storage be local filesystem first, or should it immediately share a
generic blob storage abstraction with future build artifacts?

## Verification

Cover at least these cases:

- Maven path parser handles group id, artifact id, version, classifier,
  extension, metadata, and checksum paths;
- GET returns an uploaded artifact byte-for-byte;
- HEAD returns metadata without a response body;
- missing artifacts return 404;
- unauthorized deploy is rejected;
- authorized deploy stores artifact, POM, metadata, and checksums;
- release overwrite is rejected by default;
- snapshot upload updates metadata correctly;
- concurrent uploads do not produce invalid metadata;
- a small Maven or Gradle test project can resolve an artifact from Orion over
  HTTP and HTTPS.
