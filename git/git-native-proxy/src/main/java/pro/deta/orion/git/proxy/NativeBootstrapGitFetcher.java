package pro.deta.orion.git.proxy;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.client.GitUploadPackRequest;
import pro.deta.orion.git.client.GitUploadPackResult;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

final class NativeBootstrapGitFetcher implements BootstrapGitFetcher {
    private static final String NULL_ID = "0".repeat(40);
    private static final GitClientOptions OPTIONS = GitClientOptions.defaults();
    private static final PackIngestionLimits PACK_LIMITS = new PackIngestionLimits(
            OPTIONS.maximumPackBytes(), 1_000_000, 256 * 1024 * 1024);

    @Override
    public void fetch(
            BootstrapGitLocation location,
            GitClientTransport transport,
            NativeGitRepository repository) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(repository, "repository");
        GitUploadPackClient client = new GitUploadPackClient(transport);
        GitRemoteAdvertisement advertisement = success(
                client.discover(location.remoteUri(), OPTIONS), "upstream discovery");
        GitRemoteAdvertisement.Ref remoteRef = findRef(advertisement, location.refName());
        String oldId = repository.refs().getOrDefault(location.refName(), NULL_ID);
        if (oldId.equals(remoteRef.objectId())) {
            return;
        }
        LooseObjectStore noObjects = new LooseObjectStore();
        if (repository.hasCompleteObjectClosure(
                GitObjectId.of(remoteRef.objectId()),
                noObjects)) {
            NativeFetchedRefPublisher.publish(
                    repository,
                    noObjects,
                    new LooseRefStore.Update(location.refName(), oldId, remoteRef.objectId()));
            return;
        }
        fetchPack(client, location, repository, oldId, remoteRef.objectId());
    }

    private static GitRemoteAdvertisement.Ref findRef(
            GitRemoteAdvertisement advertisement,
            String refName) {
        for (GitRemoteAdvertisement.Ref candidate : advertisement.refs()) {
            if (refName.equals(candidate.name())) {
                return candidate;
            }
        }
        throw new BootstrapGitProxyException("required ref lookup");
    }

    private static void fetchPack(
            GitUploadPackClient client,
            BootstrapGitLocation location,
            NativeGitRepository repository,
            String oldId,
            String newId) {
        try (PackIngestionSession session = repository.beginPackIngestion(PACK_LIMITS)) {
            IngestionOutput output = new IngestionOutput(session);
            GitUploadPackRequest request = new GitUploadPackRequest(
                    List.of(newId),
                    NULL_ID.equals(oldId) ? List.of() : List.of(oldId),
                    output,
                    ignored -> { });
            success(client.fetch(location.remoteUri(), OPTIONS, request), "pack transfer");
            LooseObjectStore objects = output.complete();
            NativeFetchedRefPublisher.publish(
                    repository,
                    objects,
                    new LooseRefStore.Update(location.refName(), oldId, newId));
        }
    }

    private static <T> T success(GitClientResult<T> result, String stage) {
        if (result instanceof GitClientResult.Success<T> success) {
            return success.value();
        }
        throw new BootstrapGitProxyException(stage);
    }

    private static final class IngestionOutput implements BufferedByteOutput {
        private final PackIngestionSession session;
        private PackIngestionResult result = new PackIngestionResult.NeedInput();

        private IngestionOutput(PackIngestionSession session) {
            this.session = session;
        }

        @Override
        public void write(ByteBuf buffer) throws IOException {
            result = session.accept(buffer);
            if (result instanceof PackIngestionResult.Failed) {
                throw new IOException("Remote Git pack is invalid");
            }
        }

        @Override
        public void flush() {
        }

        private LooseObjectStore complete() {
            if (result instanceof PackIngestionResult.NeedInput) {
                result = session.endOfInput();
            }
            if (result instanceof PackIngestionResult.Complete complete) {
                return complete.quarantine();
            }
            throw new BootstrapGitProxyException("pack validation");
        }
    }
}
