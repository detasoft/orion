# Interactive SSH Query and Output Design

## Goal

Add bounded filtering, column projection, pagination, and automation-oriented output formats to Orion list
commands without moving authorization or presentation concerns into domain handlers.

## Selected approach

Replace the string-only structured result cells with one typed value model and apply a generic row-query stage in
the command dispatcher after a handler returns. Domain handlers remain responsible for producing deterministically
ordered, ACL-filtered structured results. The dispatcher validates and applies query arguments to that safe result,
and renderers encode the transformed result according to its selected format.

This creates one production path for interactive and exec requests. It also makes JSON type-preserving without
keeping a parallel legacy row representation or requiring every domain catalog to implement filtering and
pagination independently.

## Typed structured values

Introduce a sealed command value with text, number, boolean, and null variants. `Rows` and `ObjectValue` use these
values directly. Replace every in-repository producer, renderer, and test of the current string-only structures in
the same change; do not retain compatibility constructors or adapters.

Text values cover ordinary strings and enums. A command definition continues to enumerate known enum values in its
completion metadata. Numbers use a representation that preserves their exact JSON spelling, and booleans and null
remain distinct through rendering. Plain, terse, and table renderers convert values to safe escaped text; null is
rendered as the literal `null`.

## Query contract

List actions that opt into querying accept these named parameters:

- `columns=<comma-separated-fields>` selects and orders output columns;
- `page=<positive-integer>` selects a one-based page;
- `page-size=<positive-integer>` selects a bounded page size;
- `format=plain|terse|json|table` selects an explicit renderer format.

The default page is 1, the default page size is 100, and the maximum page size is 500. An omitted `columns`
parameter preserves the handler's declared column order. Duplicate, empty, and unknown columns are invalid.
`format=table` is valid only for an interactive request with a usable terminal width. With no explicit format,
interactive requests use the width-aware table and exec requests use stable plain output.

Predicates remain conjunctions of `field=value` and `field!=value`. A query-enabled command declares its filterable
fields. Equality is exact and type-aware: numbers must parse as numbers, booleans accept only `true` or `false`, and
the reserved literal `null` matches a null cell. Enum values are validated against enumerated command metadata;
ordinary text is compared exactly. Unknown fields, invalid typed literals, unsupported predicates on a result type,
and query parameters on non-query commands return `INVALID_ARGUMENTS`.

## Safe data flow

The dispatcher keeps the existing ordering:

1. Parse and navigate the command tree.
2. Resolve dynamic resources and authorize the concrete action.
3. Invoke the handler, which queries its source and removes inaccessible values.
4. If the successful result is queryable rows, apply predicates to the already filtered rows.
5. Project columns, calculate accessible-result metadata, and slice the requested page.
6. Render the transformed result.

Query errors name only declared fields or supplied argument values. Counts and pagination metadata are calculated
only from rows that survived domain authorization, so hidden resources cannot affect errors, totals, page
boundaries, completion, or continuation metadata.

Existing read-only list handlers already sort by canonical ID. Query processing preserves that order and never
re-sorts by display values. Page requests beyond the last available row succeed with an empty page and stable
metadata rather than revealing a separate existence distinction.

## Pagination metadata

Queryable rows carry immutable metadata containing the one-based page number, page size, accessible matched count,
and an optional next page number. The next page is present only when more authorized matching rows remain.

Plain and table output retain their existing header and row structure. They append a comment-style metadata line
only when the result was truncated, a pagination parameter was explicit, or the requested page is not the first.
Terse output omits the header and uses one escaped tab-separated record per line, followed by the same conditional
metadata line. JSON always includes pagination metadata.

## Rendering

Stable JSON is a single UTF-8 object followed by one newline:

```json
{"columns":["id","refCount"],"rows":[{"id":"demo","refCount":3}],"page":{"number":1,"size":100,"matched":1,"next":null}}
```

Keys follow declared column order. JSON strings use standards-compliant escaping and never contain raw terminal
control characters. Numbers, booleans, and null values are emitted as their JSON types.

Stable plain output remains escaped TSV with a header. Terse output is escaped TSV without a header. Interactive
table output keeps terminal-width-aware alignment and falls back to stable plain layout when the selected columns
do not fit. Explicit plain, terse, and JSON formats are identical over PTY and exec frontends and contain no ANSI
sequences.

The selected format travels as immutable result presentation metadata. Handlers never inspect the request's
terminal or output format, and Mina-specific classes remain outside the command module.

## Completion

Queryable list definitions enumerate `columns`, `page`, `page-size`, and `format` as named parameters. Completion
offers format values, column names after `columns=`, filterable fields after `where`, and enum values after a known
enum predicate. Completion continues to use the authorized command tree and static definition metadata; it does not
query or expose inaccessible rows.

## Error handling

Malformed query arguments return `INVALID_ARGUMENTS` and exit code 2. A handler failure or unavailable source is
returned unchanged and is never replaced by a query error. Query processing applies only to successful row results;
messages, objects, streams, attachments, exits, and failures retain their existing behavior. Expected validation
and rendering failures remain structured command results rather than exceptions.

## Verification

Core tests cover typed value immutability, predicate parsing and type validation, conjunctions, projection order,
page bounds, maximum page size, empty and beyond-end pages, metadata, stable plain/terse/JSON bytes, JSON escaping,
terminal-width behavior, completion, non-query rejection, and no-PTY table rejection.

Domain catalog tests cover every list shape, enum completion, nullable values, deterministic ID ordering, and ACL
filtering before predicates, counts, and pagination. SSH frontend tests verify identical explicit automation formats
over exec and PTY, stable exit code 2 for invalid no-PTY requests, and unchanged behavior for Git commands and
non-row Orion commands.
