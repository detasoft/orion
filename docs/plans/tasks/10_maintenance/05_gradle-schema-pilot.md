# Gradle schema build-cache pilot

- Owner: codex, session 01a10b70-4984-7811-b5da-02fd849e4b35,
  branch `codex/gradle-schema-pilot-20261025`,
  worktree `.worktrees/gradle-schema-pilot-20261025`, started 2026-10-25 15:47 Europe/Amsterdam.

## Required result

Provide an executable, isolated Gradle pilot for the existing `core/schema`
production sources and unit tests, and compare its incremental behavior with
Maven on exactly the same bounded module/test scope. Keep the result on its
dedicated branch for inspection; integrating the experimental build is a
separate user decision.

The preceding full Maven investigation measured 159.81 seconds initially,
2.17–2.71 seconds unchanged, and 150.71 seconds after a comment-only edit to
`core/schema/src/test/java/pro/deta/orion/schema/acl/SettingsTest.java`.
That edit invalidated 26 downstream modules and executed 3212 JVM tests.
These full-repository times are context, not an apples-to-apples comparison
with a schema-only pilot.

## Design and boundaries

- Reuse the existing Java sources, resources, tests, dependency versions,
  Java 21 release, annotation processing, and meaningful test runtime settings.
  Include the existing test-duration recorder only if needed to preserve the
  actual schema test runtime. Do not duplicate or rewrite application code.
- Put the experimental build configuration below `build-tools/gradle-pilot`
  so normal Maven commands and root project discovery remain intact.
- Use a documented Make goal with an overridable external Gradle executable;
  pin and report the distribution used. Add no wrapper or other new executable
  scripts. Download tools outside the repository and verify their checksum.
- Enable task build caching explicitly. Distinguish task UP-TO-DATE avoidance,
  restoration FROM-CACHE after removing outputs, configuration caching, daemon
  startup, and dependency download time in results.
- Do not port a large consumer dependency graph. If real schema consumers
  require a broader migration, verify downstream invalidation with a temporary
  external consumer fixture, clearly identifying that evidence as synthetic.
- Maven remains the canonical build. No repository-wide migration, test
  filtering policy change, Git-diff test selection, or application behavior
  change is included.

## Implementation and verification

1. Inspect schema's effective compiler, dependency, and test contracts and
   establish the smallest Gradle task and dependency graph that preserves them.
2. Expose the command contract in Make help, then implement the isolated build.
3. Run the same existing schema tests with Maven and Gradle. Compare executed
   test identities/counts, not merely successful command exit codes.
4. Measure initial compilation, at least three unchanged repetitions, a
   comment-only test edit, a bytecode-changing test edit, and restoration after
   removal of build outputs. Restore all temporary source edits exactly.
5. Confirm that a changed test really reruns, unchanged production compilation
   and outputs remain reusable, and an intentionally failing existing test
   causes a nonzero command exit. Restore the original test afterward.
6. Validate downstream behavior using the smallest justified real or temporary
   consumer; distinguish limitations from measurements. Verify a production
   implementation change invalidates the relevant consumer test work.
7. Document exact reproducible commands and concise measured results in the
   pilot's README, including scope, limitations, and migration recommendation.
   Leave no temporary fixture, assertion failure, or source probe in the diff.

## Dependencies and acceptance

No dependencies on unrelated unfinished maintenance tasks. Existing unrelated
main-worktree changes must remain untouched. Acceptance requires a runnable
pilot, truthful like-for-like measurements, preserved schema test coverage,
verified task cache restoration and failure propagation, a minimal reviewed
diff, and successful checks required for the affected build and Make inputs.
