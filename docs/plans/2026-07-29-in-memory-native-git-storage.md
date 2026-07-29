# In-Memory Native Git Storage Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a process-local `GitRepositoryProvider` that creates one shared native repository per name with isolated in-memory refs and objects.

**Architecture:** `InMemoryNativeGitRepositoryProvider` owns a `ConcurrentHashMap<String, NativeGitRepository>` and uses `computeIfAbsent` for atomic creation. Each repository receives fresh `LooseRefStore` and `LooseObjectStore` instances and an unborn `HEAD` targeting `refs/heads/main`; existing `GitRepository.unwrap` exposes those stores without a new operations facade.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, `ConcurrentHashMap`

---

### Task 1: Specify repository creation and storage ownership

**Files:**
- Create: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProviderTest.java`

**Step 1: Write the failing shared-state test**

Add a test that creates `demo`, updates its unwrapped `LooseRefStore`, obtains
`demo` again, and verifies:

```java
assertThat(provider.exists("demo")).isTrue();
assertThat(second).isSameAs(first);
assertThat(second.unwrapOrThrow(LooseRefStore.class)
        .read("refs/heads/main"))
        .hasValue(GitObjectId.of(OBJECT_ID));
assertThat(second.unwrapOrThrow(LooseObjectStore.class))
        .isSameAs(first.unwrapOrThrow(LooseObjectStore.class));
```

Also assert that a newly created repository has an empty ref snapshot and a
`NativeGitRepository` handle.

**Step 2: Write the failing isolation and missing-lookup test**

Create `first` and `second`, mutate the first repository's refs and objects,
then assert that the second repository remains empty. Verify that:

```java
assertThat(provider.find("missing"))
        .isEqualTo(new Result.Failure<>(
                Result.FailureCode.NOT_FOUND,
                "Native repository does not exist: missing"));
assertThat(provider.exists("missing")).isFalse();
```

**Step 3: Run the test to verify RED**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-native-storage -am \
  -Dtest=InMemoryNativeGitRepositoryProviderTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: test compilation fails because
`InMemoryNativeGitRepositoryProvider` does not exist.

### Task 2: Implement atomic process-local repository creation

**Files:**
- Modify: `core/git-native-storage/pom.xml`
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProvider.java`

**Step 1: Add the direct provider-contract dependency**

Add `pro.deta.orion.core:common` to `core/git-native-storage/pom.xml`; the new
production class directly implements `GitRepositoryProvider` and uses
`Result`.

**Step 2: Add the minimal provider**

Implement:

```java
public final class InMemoryNativeGitRepositoryProvider
        implements GitRepositoryProvider {
    private static final String DEFAULT_HEAD = "refs/heads/main";

    private final ConcurrentMap<String, NativeGitRepository> repositories =
            new ConcurrentHashMap<>();

    @Override
    public boolean exists(String repositoryName) {
        return repositories.containsKey(requireName(repositoryName));
    }

    @Override
    public Result<GitRepository> find(String repositoryName) {
        String name = requireName(repositoryName);
        NativeGitRepository repository = repositories.get(name);
        if (repository == null) {
            return new Result.Failure<>(
                    Result.FailureCode.NOT_FOUND,
                    "Native repository does not exist: " + name);
        }
        return new Result.Success<>(repository);
    }

    @Override
    public Result<GitRepository> findOrCreate(String repositoryName) {
        String name = requireName(repositoryName);
        NativeGitRepository repository = repositories.computeIfAbsent(
                name,
                ignored -> new NativeGitRepository(
                        name,
                        "",
                        new LooseRefStore(),
                        new LooseObjectStore(),
                        Optional.of(DEFAULT_HEAD)));
        return new Result.Success<>(repository);
    }
}
```

`requireName` rejects null or blank names with
`IllegalArgumentException("repositoryName must not be blank")`.

**Step 3: Run the focused tests to verify GREEN**

Run the Task 1 Maven command outside the sandbox.

Expected: PASS.

### Task 3: Prove concurrency and validation

**Files:**
- Modify: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProviderTest.java`

**Step 1: Write the concurrent-creation test**

Submit sixteen `findOrCreate("demo")` calls to a four-thread executor and assert
that every result contains the exact same `GitRepository` instance.

**Step 2: Run the concurrent test before changing production code**

Run the focused Maven command from Task 1.

Expected: PASS because `computeIfAbsent` already provides the required atomic
creation. The test extends coverage of the selected concurrency primitive; no
production change is expected.

**Step 3: Write validation tests**

For `null`, `""`, and `" "`, assert that `exists`, `find`, and `findOrCreate`
throw `IllegalArgumentException` with `repositoryName must not be blank`.

**Step 4: Run the focused test**

Run the Task 1 Maven command outside the sandbox.

Expected: PASS.

### Task 4: Verify and finish task tracking

**Files:**
- Modify: `TASKS.md`

**Step 1: Run module verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4 -q -pl core/git-native-storage -am
```

Expected: `BUILD SUCCESS`.

**Step 2: Check the diff**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. Existing continuation-related working-tree
changes remain unstaged and unchanged.

**Step 3: Finish task tracking**

Mark the process-local provider task complete in `TASKS.md` and remove its owner
line. Do not modify the broader Tasks 1–7 roadmap item.

**Step 4: Commit the implementation when requested**

Stage only:

```bash
git add TASKS.md \
  core/git-native-storage/pom.xml \
  core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProvider.java \
  core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProviderTest.java \
  docs/plans/2026-07-29-in-memory-native-git-storage.md
git commit -m "Add in-memory native Git repository provider"
```

After the commit, run `make test` outside the sandbox as required by
`AGENTS.md`.
