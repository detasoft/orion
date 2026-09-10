# Make GitObjectId Canonical

Status: todo

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

## Detailed implementation plan

### Task 1: Make `GitObjectId` own its identity

**Files:**

- Create: `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/GitObjectIdTest.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/GitObjectId.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/exchange/LegacyReceiveCommand.java`
- Modify: native-storage and transport call sites returned by
  `rg -n "toLowerCase|equalsIgnoreCase|NULL_ID|validateObjectId" git net`

1. Add tests proving that valid lowercase input is unchanged, uppercase input
   is normalized, and both forms compare and hash equally.
2. Add tests rejecting null, non-40-character, and non-hexadecimal values.
3. Add tests for a canonical `GitObjectId.ZERO` value and `isZero()`.
4. Run:

   ```bash
   mvn test -Pdev -T 4 -q -pl git/git-native-storage -am \
     -Dtest=GitObjectIdTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected: RED because the record currently accepts unvalidated spelling and
   has no zero-ID API.
5. Normalize and validate in the compact record constructor, then add the zero
   constant and query:

   ```java
   public record GitObjectId(String value) {
       public static final GitObjectId ZERO =
               new GitObjectId("0".repeat(40));

       public GitObjectId {
           value = canonicalValue(value);
       }

       public boolean isZero() {
           return equals(ZERO);
       }
   }
   ```

6. Rerun the focused test and expect GREEN.
7. Replace consumer-side normalization, zero tests, and validation only where
   the value is already typed or should become typed at that boundary. Retain
   syntax checks for raw wire text until it is converted.
8. Run the native-storage, parser, and Git transport unit tests with `-Pdev`,
   `-T 4`, `-am`, and `-Dsurefire.failIfNoSpecifiedTests=false`.
9. Commit the value-type change and its tests as one logical commit.
