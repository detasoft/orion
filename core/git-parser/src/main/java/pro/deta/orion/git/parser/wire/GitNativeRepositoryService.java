package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceivePack;
import pro.deta.orion.git.parser.wire.continuation.exchange.LsRefsRequest;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GitNativeRepositoryService {
    static final int MAX_TAG_TRAVERSAL_DEPTH = 256;
    static final int MAX_LS_REFS_OBJECT_READS = 4096;

    private static final int TAG_TARGET_PREFIX_BYTES = 48;
    private static final String NULL_ID = "0".repeat(40);
    private static final PackIngestionLimits RECEIVE_PACK_LIMITS =
            new PackIngestionLimits(
                    100L * 1024 * 1024,
                    1_000_000,
                    64 * 1024 * 1024);

    private final NativeGitRepositoryProvider repositoryProvider;
    private final GitNativeRepositoryAccessHook accessHook;
    private final GitWireConfiguration configuration;
    private final NativePackfileUriSourceFactory packfileUriSourceFactory;

    public GitNativeRepositoryService(
            NativeGitRepositoryProvider repositoryProvider) {
        this(repositoryProvider, GitWireConfiguration.allSupported());
    }

    public GitNativeRepositoryService(
            NativeGitRepositoryProvider repositoryProvider,
            GitWireConfiguration configuration) {
        this(
                repositoryProvider,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                configuration);
    }

    public GitNativeRepositoryService(
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook) {
        this(
                repositoryProvider,
                accessHook,
                GitWireConfiguration.allSupported());
    }

    public GitNativeRepositoryService(
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration) {
        this(
                repositoryProvider,
                accessHook,
                configuration,
                NativePackfileUriSourceFactory.NONE);
    }

    public GitNativeRepositoryService(
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration,
            NativePackfileUriSourceFactory packfileUriSourceFactory) {
        this.repositoryProvider = Objects.requireNonNull(
                repositoryProvider,
                "repositoryProvider");
        this.accessHook = Objects.requireNonNull(
                accessHook,
                "accessHook");
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration");
        this.packfileUriSourceFactory = Objects.requireNonNull(
                packfileUriSourceFactory,
                "packfileUriSourceFactory");
    }

    @TestOnly
    GitWireConfiguration configuration() {
        return configuration;
    }

    public GitV1Advertisement legacyUploadPackAdvertisement(
            InitialRequestData data) {
        String repositoryPath = data.getRepositoryPath();
        NativeGitRepository repository = findOrFail(repositoryPath);
        return legacyAdvertisement(
                repository,
                uploadPackCapabilities(),
                configuration.uploadPack().symref());
    }

    private NativeGitRepository findOrFail(String repositoryPath) {
        accessHook.beforeRead(repositoryPath);
        return check(repositoryPath, repositoryProvider.find(repositoryPath));
    }

    private NativeGitRepository check(
            String repositoryPath,
            Result<NativeGitRepository> repository) {
        return switch (repository) {
            case Result.Success(NativeGitRepository repo) ->
                    repo;
            case Result.Failure<NativeGitRepository> failure ->
                    throw new IllegalStateException(
                            failureMessage(repositoryPath, failure),
                            failure.throwable());
        };
    }

    public GitV1Advertisement legacyReceivePackAdvertisement(
            InitialRequestData data) {
        String repositoryPath = data.getRepositoryPath();
        NativeGitRepository repository = check(
                repositoryPath,
                receiveRepository(repositoryPath));
        return legacyAdvertisement(
                repository,
                receivePackCapabilities(),
                false);
    }

    private Result<NativeGitRepository> findOrCreate(
            String repositoryName) {
        if (!repositoryProvider.exists(repositoryName)) {
            accessHook.beforeCreate(repositoryName);
            return repositoryProvider.create(repositoryName);
        }
        accessHook.beforeWrite(repositoryName);
        return repositoryProvider.find(repositoryName);
    }

    public NativePackProducer legacyUploadPack(
            InitialRequestData data,
            NativeFetchRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        return findOrFail(data.getRepositoryPath()).fetch(request);
    }

    public NativeFetchResponse protocolV2Fetch(
            InitialRequestData data,
            NativeFetchRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        NativeGitRepository repository = findOrFail(data.getRepositoryPath());
        return repository.fetchResponse(
                request,
                packfileUriSourceFactory.sourceFor(data, repository));
    }

    public List<GitObjectId> protocolV2FetchAcknowledgments(
            InitialRequestData data,
            NativeFetchRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        String repositoryPath = data.getRepositoryPath();
        NativeGitRepository repository = findOrFail(repositoryPath);
        List<GitObjectId> acknowledgments = new ArrayList<>();
        for (GitObjectId have : request.haves()) {
            if (repository.readObject(have).isPresent()) {
                acknowledgments.add(have);
            }
        }
        return List.copyOf(acknowledgments);
    }

    public PackIngestionSession beginLegacyReceivePack(
            InitialRequestData data) {
        Objects.requireNonNull(data, "data");
        String repositoryPath = data.getRepositoryPath();
        return check(
                repositoryPath,
                receiveRepository(repositoryPath))
                .beginPackIngestion(RECEIVE_PACK_LIMITS);
    }

    public List<ReceivePackStatus> completeLegacyReceivePack(
            LegacyReceivePack receivePack) {
        Objects.requireNonNull(receivePack, "receivePack");
        List<LooseRefStore.Update> updates = new ArrayList<>();
        for (LegacyReceiveCommand command
                : receivePack.commandSection().commands()) {
            updates.add(new LooseRefStore.Update(
                    command.refName(),
                    command.oldObjectId().value(),
                    command.newObjectId().value()));
        }
        String repositoryPath = receivePack.commandSection()
                .initialRequest()
                .getRepositoryPath();
        NativeGitRepository repository = check(
                repositoryPath,
                receiveRepository(repositoryPath));
        List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                receivePack.quarantine(),
                updates);
        List<ReceivePackStatus> statuses = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            LegacyReceiveCommand command =
                    receivePack.commandSection().commands().get(index);
            RefUpdateResult result = results.get(index);
            statuses.add(new ReceivePackStatus(
                    command.refName(),
                    result != RefUpdateResult.STALE,
                    result == RefUpdateResult.STALE
                            ? "stale"
                            : ""));
        }
        return List.copyOf(statuses);
    }

    public GitLsRefsResponse lsRefs(
            InitialRequestData data,
            LsRefsRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        String repositoryPath = data.getRepositoryPath();
        return lsRefs(findOrFail(repositoryPath), request);
    }

    private GitLsRefsResponse lsRefs(
            NativeGitRepository repository,
            LsRefsRequest request) {
        Map<String, String> refs = repository.refs();
        List<String> refNames = new ArrayList<>(refs.keySet());
        refNames.sort(String::compareTo);
        List<GitLsRefsResponse.Ref> responseRefs = new ArrayList<>();
        PeelResolver peelResolver = new PeelResolver(repository);
        if (request.matches("HEAD")) {
            String headTarget = repository.defaultHead();
            String headObjectId = refs.get(headTarget);
            if (headObjectId != null) {
                Optional<String> symrefTarget = request.symrefs()
                        ? Optional.of(headTarget)
                        : Optional.empty();
                responseRefs.add(new GitLsRefsResponse.DirectRef(
                        headObjectId,
                        "HEAD",
                        symrefTarget,
                        Optional.empty()));
            } else if (request.unborn()) {
                responseRefs.add(new GitLsRefsResponse.UnbornRef(
                        "HEAD",
                        headTarget));
            }
        }
        for (String refName : refNames) {
            if (!refName.equals("HEAD") && request.matches(refName)) {
                String objectId = refs.get(refName);
                responseRefs.add(new GitLsRefsResponse.DirectRef(
                        objectId,
                        refName,
                        Optional.empty(),
                        peeledObjectId(
                                request,
                                refName,
                                objectId,
                                peelResolver)));
            }
        }
        return new GitLsRefsResponse(responseRefs);
    }

    private static Optional<String> peeledObjectId(
            LsRefsRequest request,
            String refName,
            String objectId,
            PeelResolver peelResolver) {
        if (!request.peel() || !refName.startsWith("refs/tags/")) {
            return Optional.empty();
        }
        return peelResolver.peel(objectId);
    }

    private static final class PeelResolver {
        private final NativeGitRepository repository;
        private final Map<String, PeelOutcome> outcomes =
                new HashMap<>();
        private int objectReads;

        private PeelResolver(NativeGitRepository repository) {
            this.repository = repository;
        }

        private Optional<String> peel(String objectId) {
            PeelOutcome cached = outcomes.get(objectId);
            if (cached != null) {
                return cached.peeledObjectId();
            }
            List<String> tags = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String currentId = objectId;
            while (true) {
                PeelOutcome resolved = outcomes.get(currentId);
                if (resolved != null) {
                    return finish(objectId, tags, currentId, resolved);
                }
                if (!visited.add(currentId)) {
                    fail(tags);
                    return Optional.empty();
                }
                if (objectReads >= MAX_LS_REFS_OBJECT_READS) {
                    return Optional.empty();
                }
                objectReads++;
                Optional<LooseObjectPrefix> current =
                        repository.readObjectPrefix(
                                GitObjectId.of(currentId),
                                TAG_TARGET_PREFIX_BYTES);
                if (current.isEmpty()) {
                    outcomes.put(currentId, PeelOutcome.failed());
                    fail(tags);
                    return Optional.empty();
                }
                LooseObjectPrefix object = current.get();
                if (object.type() != ObjectType.TAG) {
                    PeelOutcome terminal = PeelOutcome.terminal();
                    outcomes.put(currentId, terminal);
                    return finish(
                            objectId,
                            tags,
                            currentId,
                            terminal);
                }
                if (tags.size() >= MAX_TAG_TRAVERSAL_DEPTH) {
                    return Optional.empty();
                }
                Optional<String> targetId =
                        leadingTagTarget(object.dataPrefix());
                if (targetId.isEmpty()) {
                    outcomes.put(currentId, PeelOutcome.failed());
                    fail(tags);
                    return Optional.empty();
                }
                tags.add(currentId);
                currentId = targetId.get();
            }
        }

        private Optional<String> finish(
                String startId,
                List<String> tags,
                String currentId,
                PeelOutcome resolved) {
            if (resolved.kind() == PeelOutcomeKind.FAILED) {
                fail(tags);
                return Optional.empty();
            }
            String peeledId = resolved.kind()
                    == PeelOutcomeKind.TERMINAL
                    ? currentId
                    : resolved.targetId();
            for (String tagId : tags) {
                outcomes.put(tagId, PeelOutcome.peeled(peeledId));
            }
            PeelOutcome start = outcomes.get(startId);
            return start == null
                    ? Optional.empty()
                    : start.peeledObjectId();
        }

        private void fail(List<String> tags) {
            for (String tagId : tags) {
                outcomes.put(tagId, PeelOutcome.failed());
            }
        }
    }

    private enum PeelOutcomeKind {
        TERMINAL,
        PEELED,
        FAILED
    }

    private record PeelOutcome(
            PeelOutcomeKind kind,
            String targetId) {
        private static PeelOutcome terminal() {
            return new PeelOutcome(PeelOutcomeKind.TERMINAL, "");
        }

        private static PeelOutcome peeled(String targetId) {
            return new PeelOutcome(PeelOutcomeKind.PEELED, targetId);
        }

        private static PeelOutcome failed() {
            return new PeelOutcome(PeelOutcomeKind.FAILED, "");
        }

        private Optional<String> peeledObjectId() {
            return kind == PeelOutcomeKind.PEELED
                    ? Optional.of(targetId)
                    : Optional.empty();
        }
    }

    private static Optional<String> leadingTagTarget(byte[] data) {
        byte[] prefix = "object ".getBytes(StandardCharsets.US_ASCII);
        int idLength = 40;
        int newlineIndex = prefix.length + idLength;
        if (data.length <= newlineIndex
                || data[newlineIndex] != '\n') {
            return Optional.empty();
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return Optional.empty();
            }
        }
        for (int i = prefix.length; i < newlineIndex; i++) {
            byte value = data[i];
            boolean digit = value >= '0' && value <= '9';
            boolean lowerHex = value >= 'a' && value <= 'f';
            boolean upperHex = value >= 'A' && value <= 'F';
            if (!digit && !lowerHex && !upperHex) {
                return Optional.empty();
            }
        }
        return Optional.of(new String(
                data,
                prefix.length,
                idLength,
                StandardCharsets.US_ASCII));
    }

    private List<GitCapability> uploadPackCapabilities() {
        GitWireConfiguration.LegacyUploadPack uploadPack =
                configuration.uploadPack();
        List<GitCapability> capabilities = new ArrayList<>();
        if (uploadPack.multiAckDetailed()) {
            capabilities.add(GitCapability.MULTI_ACK_DETAILED);
        }
        if (uploadPack.thinPack()) {
            capabilities.add(GitCapability.THIN_PACK);
        }
        if (uploadPack.sideBand64k()) {
            capabilities.add(GitCapability.SIDE_BAND_64K);
        }
        if (uploadPack.ofsDelta()) {
            capabilities.add(GitCapability.OFS_DELTA);
        }
        if (uploadPack.agent()) {
            capabilities.add(GitCapability.agent("orion-native"));
        }
        return capabilities;
    }

    private List<GitCapability> receivePackCapabilities() {
        GitWireConfiguration.LegacyReceivePack receivePack =
                configuration.receivePack();
        List<GitCapability> capabilities = new ArrayList<>();
        if (receivePack.reportStatus()) {
            capabilities.add(GitCapability.REPORT_STATUS);
        }
        if (receivePack.sideBand64k()) {
            capabilities.add(GitCapability.SIDE_BAND_64K);
        }
        if (receivePack.ofsDelta()) {
            capabilities.add(GitCapability.OFS_DELTA);
        }
        if (receivePack.objectFormat()) {
            capabilities.add(GitCapability.objectFormat("sha1"));
        }
        if (receivePack.agent()) {
            capabilities.add(GitCapability.agent("orion-native"));
        }
        return capabilities;
    }

    private GitV1Advertisement legacyAdvertisement(
            NativeGitRepository repository,
            List<GitCapability> baseCapabilities,
            boolean advertiseHeadSymref) {
        Objects.requireNonNull(repository, "repository");
        Map<String, String> refs = repository.refs();
        List<GitAdvertisedRef> advertisedRefs = new ArrayList<>();
        String headTarget = repository.defaultHead();
        String headObjectId = refs.get(headTarget);
        List<GitCapability> capabilities =
                new ArrayList<>(baseCapabilities);
        if (headObjectId != null) {
            advertisedRefs.add(
                    GitAdvertisedRef.direct(headObjectId, "HEAD"));
            if (advertiseHeadSymref) {
                capabilities.add(GitCapability.symref(
                        "HEAD",
                        headTarget));
            }
        }
        List<String> refNames = new ArrayList<>(refs.keySet());
        refNames.sort(String::compareTo);
        for (String refName : refNames) {
            advertisedRefs.add(GitAdvertisedRef.direct(
                    refs.get(refName),
                    refName));
        }
        if (advertisedRefs.isEmpty()) {
            advertisedRefs.add(GitAdvertisedRef.direct(
                    NULL_ID,
                    "capabilities^{}"));
        }
        return new GitV1Advertisement(capabilities, advertisedRefs);
    }

    private Result<NativeGitRepository> receiveRepository(
            String repositoryName) {
        accessHook.beforeReceive(repositoryName);
        return findOrCreate(repositoryName);
    }

    private static String failureMessage(
            String repositoryName,
            Result.Failure<NativeGitRepository> failure) {
        String message = failure.message();
        if (message == null || message.isBlank()) {
            return "Cannot resolve native repository " + repositoryName;
        }
        return message;
    }

    public record ReceivePackStatus(
            String refName,
            boolean ok,
            String message) {
        public ReceivePackStatus {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(message, "message");
        }
    }
}
