# Canonical Repository Names Design

## Goal

Give every repository ingress one storage-neutral definition of repository identity. Authorization, bootstrap,
Git transports, ACL storage, and native repository providers must use the same canonical string before selecting
or mutating a repository.

Migration of previously persisted noncanonical names is out of scope. Such state fails validation instead of
activating a compatibility, fallback, or dual-read path.

## Canonical Contract

Add `RepositoryName` to `core/schema`, below transport and storage modules. It owns percent decoding, separator
normalization, and validation and exposes the resulting canonical string. Existing internal APIs continue to take
`String`; their ingress and provider boundaries obtain that string from `RepositoryName` rather than propagating a
new value type through unrelated APIs.

A canonical name contains one or more `/`-separated segments. Segment count is not fixed, so current names such as
`orion` and `internal/configuration` remain expressible alongside the planned `org/team/repo` address. Every segment
uses the existing Orion identifier rule:

```text
[a-z0-9]+(?:[._-][a-z0-9]+)*
```

This permits lowercase ASCII letters, digits, and single internal `.`, `_`, or `-` separators. It rejects uppercase,
Unicode, whitespace, empty segments, leading or trailing punctuation, adjacent punctuation, and the `.` and `..`
segments. No case folding or whitespace trimming occurs.

Input is percent-decoded as UTF-8 exactly once. A raw `%` must start a valid `%HH` byte escape. Malformed escapes,
invalid UTF-8, a decoded character outside the canonical alphabet, and a percent sign left by double encoding are
rejected. Raw `+` has no form-encoding meaning and is rejected by the alphabet. After decoding, `\` is normalized to
`/`, so `team%2Frepo`, `team%5Crepo`, `team\repo`, and `team/repo` identify `team/repo`.

The ordinary-name entry point rejects leading and trailing separators and reserves a terminal `.git`; the suffix is
not part of stored identity. The Git-path entry point may remove exactly one leading separator and exactly one
terminal `.git` after decoding and separator normalization, then applies the ordinary canonical validation. Thus
`/org/team/repo.git` becomes `org/team/repo`, while `//repo`, `repo.git.git`, and encoded traversal remain invalid.

`RepositoryAddress` remains the exact three-segment Orion hierarchy type. `RepositoryName` is the qualified runtime
identity needed while one- and two-segment repositories still exist; both reuse the same segment rule rather than
defining competing alphabets.

## Production Flow

- Git wire parsing replaces `GitWireBootstrap.normalizeRepositoryPath` with the shared Git-path entry point. The old
  public normalization path and its real consumers are removed together.
- HTTP Git and admin routes canonicalize before authorization and provider lookup. The string used for the grant
  decision is exactly the string passed to the native provider.
- The frontend sends the repository name entered by the operator unchanged to the admin route. It does not trim,
  remove transport decoration, or maintain a JavaScript copy of the canonicalization policy.
- Bootstrap and proxy resolution replace `Path.of(...).normalize()` repository-name checks with the ordinary-name
  entry point. `ResolvedBootstrapSource.repositoryName()` contains only the canonical string.
- Native providers apply the ordinary-name entry point to `exists`, `find`, and `create` calls and validate names read
  from persisted metadata. This is a defensive boundary using the same parser, not a second validation policy.
- ACL storage consumes the canonical resolved identity. No connector-local parser is restored, and `acl-storage`
  does not depend on `git-parser`.
- Authorization and configuration ingresses that construct repository identities use the same contract; code that
  only transports an already canonical value does not add another representation.

Invalid input is rejected before authorization-dependent provider access or filesystem I/O. Existing
`IllegalArgumentException` control flow remains: HTTP maps invalid requests to a client error, bootstrap fails
startup, and direct provider calls fail at their boundary. No new error hierarchy is introduced.

## Persistence and Compatibility

Native repository metadata stores the canonical string, and reopening a repository yields that same value. Existing
metadata, proxy catalog entries, or configured names that do not satisfy the new contract fail fast. The change does
not scan, rename, alias, decode twice, or otherwise migrate old identities.

## Verification

Shared contract tests cover:

- plain, dotted, underscored, dashed, nested, and three-segment names;
- percent-encoded slash, backslash, dot, and allowed characters;
- one Git leading separator and optional `.git` decoration;
- malformed and double percent encoding, invalid UTF-8, uppercase, Unicode, whitespace, `+`, empty segments,
  traversal segments, repeated punctuation, extra leading separators, and repeated `.git` suffixes.

Ingress tests cover Git wire, HTTP admin and Git routes, the frontend admin client, bootstrap/proxy resolution, and
native providers. They prove that invalid input does not reach a provider, that the frontend does not rewrite the
operator's spelling, and that authorization and provider selection receive the same canonical identity. Storage
tests cover canonical metadata across reopen and fail-fast behavior for invalid persisted metadata. Dependency
verification confirms that `acl-storage` does not depend on `git-parser` and that the shared contract has no
transport dependency.

Focused tests run through the repository `make run-test` target. Routine development verification uses
`mvn verify -Pdev -T 4`; after the implementation commit is integrated on `main`, `make test` provides the required
post-commit project check.

## Non-goals

This change does not implement repository-name migration, enforce exactly three address segments, change ACL grant
semantics beyond using the canonical identity, or repair Local ACL filesystem containment and publication. Those
Local storage repairs remain separate dependent work.
