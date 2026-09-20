package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

@Singleton
public final class DefaultGitNativeRepositoryService {
    private final NativeGitRepositoryProvider repositoryProvider;

    @Inject
    public DefaultGitNativeRepositoryService(NativeGitRepositoryProvider repositoryProvider) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
    }

    public GitRepositoryContext open(InitialRequestData request, GitNativeRepositoryAccessHook accessHook)
            throws IOException {
        return open(request, accessHook, Optional.empty());
    }

    public GitRepositoryContext open(InitialRequestData request, GitNativeRepositoryAccessHook accessHook,
            Optional<String> packUriBase) throws IOException {
        Objects.requireNonNull(accessHook, "accessHook");
        String name = request.repositoryPath();
        if (!repositoryProvider.isPublicRepositoryName(name)) {
            throw new GitNativeRepositoryAccessHook.AccessDeniedException(
                    "Repository is not publicly accessible", null);
        }
        Result<NativeGitRepository> result;
        if (request.service() == InitialRequestService.RECEIVE_PACK) {
            accessHook.beforeReceive(name);
            if (repositoryProvider.exists(name)) {
                accessHook.beforeWrite(name);
                result = repositoryProvider.openForWrite(name);
            } else {
                accessHook.beforeCreate(name);
                result = repositoryProvider.create(name);
            }
        } else {
            accessHook.beforeRead(name);
            result = repositoryProvider.openForRead(name);
        }
        NativeGitRepository repository = switch (result) {
            case Result.Success(NativeGitRepository value) -> value;
            case Result.Failure<NativeGitRepository> failure -> throw new IOException(
                    failure.message() == null ? "Cannot resolve repository " + name : failure.message(),
                    failure.throwable());
        };
        return new NativeGitRepositoryContext(name, repository, repositoryProvider, accessHook, packUriBase);
    }
}
