package pro.deta.orion.git.nativestorage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Objects;

/** Creates ephemeral repositories; their owner keeps the only registry. */
final class InMemoryNativeGitRepositoryFactory implements NativeGitRepositoryBackend {
    private final OrionEventManager events;

    InMemoryNativeGitRepositoryFactory(OrionEventManager events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public List<String> repositoryNames() {
        return List.of();
    }

    @Override
    public boolean exists(RepositoryName name) {
        return false;
    }

    @Override
    public Result<NativeGitRepository> open(RepositoryName name) {
        return new Result.Failure<>(Result.FailureCode.NOT_FOUND,
                "In-memory repository does not exist: " + name.value());
    }

    @Override
    public Result<NativeGitRepository> create(RepositoryName name) {
        return new Result.Success<>(NativeGitRepository.createInMemory(name, events));
    }

    @Override
    public void close() {
    }
}
