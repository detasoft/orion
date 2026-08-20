package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.common.GitCommitAuthor;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRepositoryFileSnapshot;
import pro.deta.orion.git.common.GitUploadRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class NativeGitRepositoryAdapter implements GitRepository {
    private final NativeGitRepository repository;

    public NativeGitRepositoryAdapter(NativeGitRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public String name() {
        return repository.name();
    }

    @Override
    public String description() {
        return repository.description();
    }

    @Override
    public GitRepository withFetchAccessCheck(
            Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        repository.withFetchAccessCheck(fetchAccessCheck);
        return this;
    }

    @Override
    public void upload(
            GitUploadRequest request,
            InputStream input,
            OutputStream output,
            OutputStream error) throws IOException, GitOperationException {
        repository.upload(request, input, output, error);
    }

    @Override
    public void receive(
            GitReceiveRequest request,
            InputStream input,
            OutputStream output,
            OutputStream error) throws IOException, GitOperationException {
        repository.receive(request, input, output, error);
    }

    @Override
    public GitRepositoryFileSnapshot loadFiles(String branch, List<String> paths)
            throws IOException, GitOperationException {
        return new NativeRepositoryFileLoader(repository).loadFiles(branch, paths);
    }

    @Override
    public void saveFiles(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws IOException, GitOperationException {
        new NativeRepositoryFileSaver(repository).saveFiles(branch, files, message, author);
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> repositoryType) {
        Objects.requireNonNull(repositoryType, "repositoryType");
        if (repositoryType.isInstance(this)) {
            return Optional.of(repositoryType.cast(this));
        }
        return repository.unwrap(repositoryType);
    }

    @Override
    public void close() {
        repository.close();
    }
}
