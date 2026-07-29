# In-Memory Native Git Storage Design

## Scope

Add only the process-local repository provider and its low-level storage
ownership. This slice does not add typed fetch or receive operations and does
not connect storage to wire machines, continuations, or transport handlers.

## Architecture

`InMemoryNativeGitRepositoryProvider` implements the existing
`GitRepositoryProvider` contract. It owns a concurrent map from validated
repository names to `NativeGitRepository` instances.

`findOrCreate` atomically creates one repository per name. Every created
repository owns a new `LooseRefStore`, a new `LooseObjectStore`, and an unborn
`HEAD` targeting `refs/heads/main`. Repeated calls for one name return the same
repository and therefore observe the same refs and objects. Different names
never share storage.

The existing `GitRepository.unwrap` contract remains the low-level access point
for `LooseRefStore` and `LooseObjectStore`. No additional operations facade is
introduced in this slice.

## Validation and Errors

Null and blank repository names are rejected before map access. `find` never
creates state: it returns `Result.Failure` with `NOT_FOUND` for an unknown
repository. Closing a returned repository handle does not remove process-local
provider state.

## Concurrency

The provider uses `ConcurrentHashMap.computeIfAbsent` so concurrent creation of
one name publishes one shared repository instance. The existing ref and object
stores retain responsibility for their own thread safety.

## Testing

Provider tests cover:

- finding and creating a repository;
- repeated lookup preserving refs and objects;
- concurrent creation of one shared repository;
- storage isolation between different names;
- missing lookup without implicit creation;
- rejection of null and blank names.

Focused verification runs the provider test in `core/git-native-storage`. Final
development verification runs the module and its required reactor dependencies.
