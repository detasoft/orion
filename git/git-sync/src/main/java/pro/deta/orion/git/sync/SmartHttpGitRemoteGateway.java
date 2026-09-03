package pro.deta.orion.git.sync;

import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.client.GitUploadPackRequest;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionOutput;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore.Update;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.NativeFetchOptions;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class SmartHttpGitRemoteGateway implements GitRemoteGateway {
    private static final String NULL_ID = "0".repeat(40);
    private static final String HEAD_PREFIX = "refs/heads/";
    private static final String TRACKING_PREFIX = "refs/remotes/upstream/";
    private static final PackIngestionLimits PACK_LIMITS =
            new PackIngestionLimits(
                    4L * 1024 * 1024 * 1024,
                    1_000_000,
                    64 * 1024 * 1024);

    private final GitRemoteConnection connection;

    public SmartHttpGitRemoteGateway(GitRemoteConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public GitFetchedHeads fetchHeads(NativeGitRepository repository)
            throws GitRemoteException {
        NativeGitRepository checked = Objects.requireNonNull(
                repository,
                "repository");
        Map<String, String> heads = listHeads();
        if (heads.isEmpty()) {
            return new GitFetchedHeads(heads);
        }
        Set<String> wants = new LinkedHashSet<>(heads.values());
        Set<String> haves = new LinkedHashSet<>(checked.refs().values());
        try (PackIngestionOutput target = new PackIngestionOutput(
                checked.beginPackIngestion(PACK_LIMITS))) {
            GitUploadPackRequest request = new GitUploadPackRequest(
                    List.copyOf(wants),
                    List.copyOf(haves),
                    target,
                    ignored -> { });
            requireSuccess(
                    connection.uploadPack().fetch(
                            connection.uri(),
                            connection.options(),
                            request),
                    "fetch");
            LooseObjectStore quarantine = target.complete().quarantine();
            publishTrackingRefs(checked, heads, quarantine);
            return new GitFetchedHeads(heads);
        } catch (IOException | RuntimeException error) {
            throw GitRemoteException.local("fetch publication", true, error);
        }
    }

    @Override
    public Map<String, String> listHeads() throws GitRemoteException {
        GitRemoteAdvertisement advertisement = requireSuccess(
                connection.uploadPack().discover(
                        connection.uri(),
                        connection.options()),
                "head discovery");
        return advertisedHeads(advertisement);
    }

    @Override
    public GitPushOutcome pushHead(
            NativeGitRepository repository,
            String refName,
            String expectedRemoteId,
            String desiredId) throws GitRemoteException {
        NativeGitRepository checked = Objects.requireNonNull(
                repository,
                "repository");
        GitBranchPlan.requireHead(refName);
        Objects.requireNonNull(desiredId, "desiredId");
        GitRemoteAdvertisement advertisement = requireSuccess(
                connection.receivePack().discover(
                        connection.uri(),
                        connection.options()),
                "push discovery");
        String observed = advertisement.findRef(refName)
                .map(GitRemoteAdvertisement.Ref::objectId)
                .orElse(null);
        if (desiredId.equals(observed)) {
            publishTrackingRef(checked, refName, desiredId);
            return new GitPushOutcome(
                    GitPushOutcome.Status.ALREADY_CURRENT,
                    Optional.of(observed));
        }
        if (!Objects.equals(expectedRemoteId, observed)) {
            return new GitPushOutcome(
                    GitPushOutcome.Status.REMOTE_CHANGED,
                    Optional.ofNullable(observed));
        }
        GitReceivePackRequest request = pushRequest(
                checked,
                refName,
                observed,
                desiredId);
        GitReceivePackResult result = requireSuccess(
                connection.receivePack().push(
                        connection.uri(),
                        connection.options(),
                        request),
                "push");
        GitPushOutcome.Status status = result.accepted()
                ? GitPushOutcome.Status.APPLIED
                : GitPushOutcome.Status.REJECTED;
        if (status == GitPushOutcome.Status.APPLIED) {
            publishTrackingRef(checked, refName, desiredId);
        }
        return new GitPushOutcome(status, Optional.ofNullable(observed));
    }

    private static void publishTrackingRef(
            NativeGitRepository repository,
            String headRef,
            String desiredId) throws GitRemoteException {
        String trackingRef = TRACKING_PREFIX
                + headRef.substring(HEAD_PREFIX.length());
        String expected = repository.refs().getOrDefault(trackingRef, NULL_ID);
        RefUpdateResult result = repository.updateRef(
                trackingRef,
                expected,
                desiredId);
        if (result == RefUpdateResult.STALE) {
            throw GitRemoteException.local("tracking ref publication", true, null);
        }
    }

    private static GitReceivePackRequest pushRequest(
            NativeGitRepository repository,
            String refName,
            String observed,
            String desiredId) {
        Set<GitObjectId> haves = observed == null
                ? Set.of()
                : Set.of(GitObjectId.of(observed));
        NativeFetchRequest packRequest = new NativeFetchRequest(
                Set.of(GitObjectId.of(desiredId)),
                haves,
                true,
                Set.of(),
                NativeFetchOptions.DEFAULT);
        GitReceivePackRequest.Command command = new GitReceivePackRequest.Command(
                observed == null ? NULL_ID : observed,
                desiredId,
                refName);
        return new GitReceivePackRequest(
                List.of(command),
                output -> {
                    try (NativePackProducer producer = repository.fetch(packRequest)) {
                        producer.writeTo(output);
                    }
                });
    }

    private static void publishTrackingRefs(
            NativeGitRepository repository,
            Map<String, String> heads,
            LooseObjectStore quarantine) throws GitRemoteException {
        Map<String, String> existing = repository.refs();
        List<Update> updates = new ArrayList<>();
        for (Map.Entry<String, String> entry : heads.entrySet()) {
            String trackingRef = TRACKING_PREFIX
                    + entry.getKey().substring(HEAD_PREFIX.length());
            updates.add(new Update(
                    trackingRef,
                    existing.getOrDefault(trackingRef, NULL_ID),
                    entry.getValue()));
        }
        List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                quarantine,
                updates);
        if (results.contains(RefUpdateResult.STALE)) {
            throw GitRemoteException.local("tracking ref publication", true, null);
        }
    }

    private static Map<String, String> advertisedHeads(
            GitRemoteAdvertisement advertisement) {
        Map<String, String> heads = new TreeMap<>();
        for (GitRemoteAdvertisement.Ref ref : advertisement.refs()) {
            if (ref.name().startsWith(HEAD_PREFIX)) {
                heads.put(ref.name(), ref.objectId());
            }
        }
        return Map.copyOf(heads);
    }

    private static <T> T requireSuccess(
            GitClientResult<T> result,
            String operation) throws GitRemoteException {
        if (result instanceof GitClientResult.Success<T> success) {
            return success.value();
        }
        GitClientResult.Failed<T> failed = (GitClientResult.Failed<T>) result;
        throw GitRemoteException.client(operation, failed.failure());
    }

    @Override
    public void close() {
        connection.close();
    }
}
