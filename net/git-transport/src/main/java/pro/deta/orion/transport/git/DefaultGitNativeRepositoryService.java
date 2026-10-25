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
import pro.deta.orion.auth.StorageManagement;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
        return open(request, accessHook, packUriBase, () -> new StorageManagement.Success<>(null));
    }

    public GitRepositoryContext open(InitialRequestData request, GitNativeRepositoryAccessHook accessHook,
            Optional<String> packUriBase, Supplier<StorageManagement.Outcome<Void>> preparation) throws IOException {
        Objects.requireNonNull(accessHook, "accessHook");
        Objects.requireNonNull(preparation, "preparation");
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
                if (Objects.requireNonNull(preparation.get(), "preparation result")
                        instanceof StorageManagement.Failure<Void> failure) {
                    if (failure.code() == StorageManagement.FailureCode.DENIED) {
                        throw new GitNativeRepositoryAccessHook.AccessDeniedException(failure.message(), null);
                    }
                    throw new IOException(failure.message());
                }
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
