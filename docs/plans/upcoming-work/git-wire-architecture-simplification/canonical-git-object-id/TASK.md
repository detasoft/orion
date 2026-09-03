# Make GitObjectId Canonical

Status: todo
Plan: [Git wire architecture simplification](../../../2026-09-03-git-wire-architecture-simplification.md)

Make `GitObjectId` the source of truth for the format and identity of a native
Git object ID.

## Scope

- Validate the supported 40-hex SHA-1 form when a `GitObjectId` is created.
- Normalize accepted hexadecimal text to lowercase so equality and hash codes
  represent Git object identity rather than caller spelling.
- Provide a canonical zero object ID and an `isZero()` query.
- Replace local lowercase conversions, case-insensitive typed comparisons,
  repeated zero constants, and duplicate validation where a `GitObjectId`
  already exists or can be constructed at the boundary.
- Keep raw wire and persistence conversion explicit at their boundaries.
- Coordinate type placement with the parser/storage boundary without creating
  a new shared module solely for this one value type.

## Non-Goals

- Do not add SHA-256 object-format support or a general hash-algorithm
  abstraction in this task.
- Do not convert unrelated client-facing string APIs unless they already pass
  through the native object-ID domain.

## Completion Criteria

- Tests cover lowercase and uppercase input, invalid length, invalid digits,
  canonical equality and hashing, and the zero ID.
- Production code no longer normalizes or revalidates an already constructed
  `GitObjectId`.
