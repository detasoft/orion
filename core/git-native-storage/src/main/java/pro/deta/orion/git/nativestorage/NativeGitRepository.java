package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitOperationException;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.common.GitUploadStats;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.upload.NativeUploadPackService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class NativeGitRepository implements GitRepository {
    private final String name;
    private final String description;
    private final LooseRefStore refs;
    private final LooseObjectStore objects;
    private final Optional<String> headTarget;
    private final ByteBufAllocator allocator;
    private final Consumer<GitFetchAccessRequest> fetchAccessCheck;

    public NativeGitRepository(
            String name,
            String description,
            LooseRefStore refs,
            LooseObjectStore objects,
            Optional<String> headTarget) {
        this(
                name,
                description,
                refs,
                objects,
                headTarget,
                UnpooledByteBufAllocator.DEFAULT,
                ignored -> {
                });
    }

    private NativeGitRepository(
            String name,
            String description,
            LooseRefStore refs,
            LooseObjectStore objects,
            Optional<String> headTarget,
            ByteBufAllocator allocator,
            Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNullElse(description, "");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.headTarget = Objects.requireNonNull(headTarget, "headTarget");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.fetchAccessCheck = Objects.requireNonNull(fetchAccessCheck, "fetchAccessCheck");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public GitRepository withFetchAccessCheck(Consumer<GitFetchAccessRequest> fetchAccessCheck) {
        return new NativeGitRepository(
                name,
                description,
                refs,
                objects,
                headTarget,
                allocator,
                fetchAccessCheck);
    }

    @Override
    public void upload(GitUploadRequest request, InputStream input, OutputStream output, OutputStream error)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Optional<GitUploadStats> stats = new NativeUploadPackService(
                allocator,
                name,
                refs,
                objects,
                headTarget,
                fetchAccessCheck)
                .serve(input, output);
        stats.ifPresent(request.afterUpload());
    }

    @Override
    public void receive(GitReceiveRequest request, InputStream input, OutputStream output, OutputStream error)
            throws IOException, GitOperationException {
        throw new GitOperationException("Native receive-pack is not implemented yet");
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> repositoryType) {
        Objects.requireNonNull(repositoryType, "repositoryType");
        if (repositoryType.isInstance(this)) {
            return Optional.of(repositoryType.cast(this));
        }
        if (repositoryType.isInstance(refs)) {
            return Optional.of(repositoryType.cast(refs));
        }
        if (repositoryType.isInstance(objects)) {
            return Optional.of(repositoryType.cast(objects));
        }
        return Optional.empty();
    }

    @Override
    public void close() {
    }
}
