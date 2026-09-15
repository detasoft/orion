package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativeObjectClosure;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.NativeGitReceivePack;
import pro.deta.orion.git.nativestorage.receive.ReceivePackStatus;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceivePack;
import pro.deta.orion.git.parser.wire.exchange.LsRefsRequest;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Singleton
public final class DefaultGitNativeRepositoryService
        implements GitNativeRepositoryService {
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

    @Inject
    public DefaultGitNativeRepositoryService(
            NativeGitRepositoryProvider repositoryProvider) {
        this.repositoryProvider = Objects.requireNonNull(
                repositoryProvider,
                "repositoryProvider");
    }

    @Override
    public GitV1Advertisement legacyUploadPackAdvertisement(
            InitialRequestData data,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(configuration, "configuration");
        String repositoryPath = data.getRepositoryPath();
        NativeGitRepository repository = findOrFail(
                repositoryPath,
                accessHook);
        return legacyAdvertisement(
                repository,
                uploadPackCapabilities(configuration),
                configuration.uploadPack().symref());
    }

    private NativeGitRepository findOrFail(
            String repositoryPath,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(accessHook, "accessHook");
        requirePublicRepository(repositoryPath);
        accessHook.beforeRead(repositoryPath);
        return check(repositoryPath, repositoryProvider.openForRead(repositoryPath));
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

    @Override
    public GitV1Advertisement legacyReceivePackAdvertisement(
            InitialRequestData data,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(configuration, "configuration");
        String repositoryPath = data.getRepositoryPath();
        NativeGitRepository repository = check(
                repositoryPath,
                receiveRepository(repositoryPath, accessHook));
        return legacyAdvertisement(
                repository,
                receivePackCapabilities(configuration),
                false);
    }

    private Result<NativeGitRepository> findOrCreate(
            String repositoryName,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(accessHook, "accessHook");
        if (!repositoryProvider.exists(repositoryName)) {
            accessHook.beforeCreate(repositoryName);
            return repositoryProvider.create(repositoryName);
        }
        accessHook.beforeWrite(repositoryName);
        return repositoryProvider.openForWrite(repositoryName);
    }

    @Override
    public NativePackProducer legacyUploadPack(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        return fetchRepository(data, request, accessHook)
                .fetch(request);
    }

    @Override
    public NativeFetchResponse legacyUploadFetch(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        return fetchRepository(data, request, accessHook)
                .fetchResponse(request);
    }

    @Override
    public NativeFetchResponse protocolV2Fetch(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook,
            NativePackfileUriSourceFactory packfileUriSourceFactory) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(
                packfileUriSourceFactory,
                "packfileUriSourceFactory");
        NativeGitRepository repository = fetchRepository(
                data,
                request,
                accessHook);
        return repository.fetchResponse(
                request,
                packfileUriSourceFactory.sourceFor(data, repository));
    }

    @Override
    public List<GitObjectId> protocolV2FetchAcknowledgments(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        NativeGitRepository repository = fetchRepository(
                data,
                request,
                accessHook);
        return commonHaves(repository, request.haves());
    }

    @Override
    public List<GitObjectId> commonHaves(
            InitialRequestData data,
            Iterable<GitObjectId> haves,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(haves, "haves");
        String repositoryPath = data.getRepositoryPath();
        NativeGitRepository repository = findOrFail(
                repositoryPath,
                accessHook);
        return commonHaves(repository, haves);
    }

    private static List<GitObjectId> commonHaves(
            NativeGitRepository repository,
            Iterable<GitObjectId> haves) {
        List<GitObjectId> acknowledgments = new ArrayList<>();
        for (GitObjectId have : haves) {
            Objects.requireNonNull(have, "have");
            if (repository.readObject(have).isPresent()) {
                acknowledgments.add(have);
            }
        }
        return List.copyOf(acknowledgments);
    }

    private NativeGitRepository fetchRepository(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook) {
        String repositoryPath = data.getRepositoryPath();
        NativeGitRepository repository = findOrFail(
                repositoryPath,
                accessHook);
        authorizeFetch(repositoryPath, repository, request, accessHook);
        return repository;
    }

    private static void authorizeFetch(
            String repositoryPath,
            NativeGitRepository repository,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook) {
        Set<GitObjectId> wants = new LinkedHashSet<>(request.wants());
        Map<String, String> refs = repository.refs();
        for (String wantRef : request.wantRefs()) {
            String refName = "HEAD".equals(wantRef)
                    ? effectiveHeadTarget(repository, refs)
                    : wantRef;
            String objectId = refs.get(refName);
            if (objectId == null) {
                accessHook.beforeFetch(repositoryPath, List.of());
                continue;
            }
            wants.add(GitObjectId.of(objectId));
        }
        Map<GitObjectId, List<String>> branchNames =
                resolveBranchNames(repository, wants, refs);
        for (GitObjectId want : wants) {
            accessHook.beforeFetch(
                    repositoryPath,
                    branchNames.getOrDefault(want, List.of()));
        }
    }

    private static Map<GitObjectId, List<String>> resolveBranchNames(
            NativeGitRepository repository,
            Set<GitObjectId> wants,
            Map<String, String> refs) {
        Map<GitObjectId, List<String>> resolved = new LinkedHashMap<>();
        for (GitObjectId want : wants) {
            resolved.put(want, new ArrayList<>());
        }
        if (wants.isEmpty()) {
            return resolved;
        }
        List<String> branchRefs = new ArrayList<>();
        for (String refName : refs.keySet()) {
            if (refName.startsWith("refs/heads/")) {
                branchRefs.add(refName);
            }
        }
        Collections.sort(branchRefs);
        NativeObjectClosure closure = new NativeObjectClosure(repository::readObject);
        for (String branchRef : branchRefs) {
            GitObjectId branchTip = GitObjectId.of(refs.get(branchRef));
            Set<GitObjectId> reachable =
                    closure.existingObjectIdsReachableFrom(Set.of(branchTip));
            for (GitObjectId want : wants) {
                if (reachable.contains(want)) {
                    resolved.get(want).add(
                            branchRef.substring("refs/heads/".length()));
                }
            }
        }
        for (Map.Entry<GitObjectId, List<String>> entry : resolved.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }
        return resolved;
    }

    @Override
    public boolean legacyUploadReady(
            InitialRequestData data,
            Iterable<GitObjectId> wants,
            Iterable<GitObjectId> commonHaves,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(commonHaves, "commonHaves");
        NativeGitRepository repository = findOrFail(
                data.getRepositoryPath(),
                accessHook);
        return repository.legacyUploadReady(wants, commonHaves);
    }

    @Override
    public PackIngestionSession beginLegacyReceivePack(
            InitialRequestData data,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(data, "data");
        String repositoryPath = data.getRepositoryPath();
        return check(
                repositoryPath,
                receiveRepository(repositoryPath, accessHook))
                .beginPackIngestion(RECEIVE_PACK_LIMITS);
    }

    @Override
    public List<ReceivePackStatus> completeLegacyReceivePack(
            LegacyReceivePack receivePack,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(receivePack, "receivePack");
        List<LegacyReceiveCommand> commands =
                receivePack.commandSection().commands();
        String repositoryPath = receivePack.commandSection()
                .initialRequest()
                .getRepositoryPath();
        NativeGitRepository repository = check(
                repositoryPath,
                receiveRepository(repositoryPath, accessHook));
        List<LooseRefStore.Update> updates = new ArrayList<>(commands.size());
        for (LegacyReceiveCommand command : commands) {
            updates.add(new LooseRefStore.Update(
                    command.refName(), command.oldObjectId().value(), command.newObjectId().value()));
        }
        boolean atomic = receivePack.commandSection().negotiated(GitCapability.ATOMIC);
        return NativeGitReceivePack.complete(
                repositoryPath, repository, receivePack.pack().quarantine(), updates, atomic, accessHook,
                valid -> repositoryProvider.publish(repositoryPath, receivePack.pack(), valid, atomic));
    }

    @Override
    public GitLsRefsResponse lsRefs(
            InitialRequestData data,
            LsRefsRequest request,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        String repositoryPath = data.getRepositoryPath();
        return lsRefs(findOrFail(repositoryPath, accessHook), request);
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
            String headTarget = effectiveHeadTarget(repository, refs);
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

    private static List<GitCapability.Entry> uploadPackCapabilities(
            GitWireConfiguration configuration) {
        GitWireConfiguration.LegacyUploadPack uploadPack =
                configuration.uploadPack();
        List<GitCapability.Entry> capabilities = new ArrayList<>();
        if (uploadPack.multiAckDetailed()) {
            capabilities.add(GitCapability.MULTI_ACK_DETAILED.entry());
            capabilities.add(GitCapability.MULTI_ACK.entry());
        }
        if (uploadPack.thinPack()) {
            capabilities.add(GitCapability.THIN_PACK.entry());
        }
        if (uploadPack.sideBand64k()) {
            capabilities.add(GitCapability.SIDE_BAND_64K.entry());
        }
        if (uploadPack.ofsDelta()) {
            capabilities.add(GitCapability.OFS_DELTA.entry());
        }
        capabilities.add(GitCapability.SHALLOW.entry());
        capabilities.add(GitCapability.DEEPEN_SINCE.entry());
        capabilities.add(GitCapability.DEEPEN_NOT.entry());
        capabilities.add(GitCapability.DEEPEN_RELATIVE.entry());
        capabilities.add(GitCapability.NO_PROGRESS.entry());
        capabilities.add(GitCapability.INCLUDE_TAG.entry());
        if (uploadPack.agent()) {
            capabilities.add(GitCapability.AGENT.withValue("orion-native"));
        }
        return capabilities;
    }

    private static List<GitCapability.Entry> receivePackCapabilities(
            GitWireConfiguration configuration) {
        GitWireConfiguration.LegacyReceivePack receivePack =
                configuration.receivePack();
        List<GitCapability.Entry> capabilities = new ArrayList<>();
        if (receivePack.reportStatus()) {
            capabilities.add(GitCapability.REPORT_STATUS.entry());
            capabilities.add(GitCapability.REPORT_STATUS_V2.entry());
        }
        capabilities.add(GitCapability.DELETE_REFS.entry());
        if (receivePack.sideBand64k()) {
            capabilities.add(GitCapability.SIDE_BAND_64K.entry());
        }
        capabilities.add(GitCapability.QUIET.entry());
        if (receivePack.atomic()) {
            capabilities.add(GitCapability.ATOMIC.entry());
        }
        if (receivePack.ofsDelta()) {
            capabilities.add(GitCapability.OFS_DELTA.entry());
        }
        if (receivePack.objectFormat()) {
            capabilities.add(GitCapability.OBJECT_FORMAT.withValue("sha1"));
        }
        if (receivePack.agent()) {
            capabilities.add(GitCapability.AGENT.withValue("orion-native"));
        }
        return capabilities;
    }

    private GitV1Advertisement legacyAdvertisement(
            NativeGitRepository repository,
            List<GitCapability.Entry> baseCapabilities,
            boolean advertiseHeadSymref) {
        Objects.requireNonNull(repository, "repository");
        Map<String, String> refs = repository.refs();
        List<GitAdvertisedRef> advertisedRefs = new ArrayList<>();
        String headTarget = effectiveHeadTarget(repository, refs);
        String headObjectId = refs.get(headTarget);
        List<GitCapability.Entry> capabilities =
                new ArrayList<>(baseCapabilities);
        if (headObjectId != null) {
            advertisedRefs.add(
                    GitAdvertisedRef.direct(headObjectId, "HEAD"));
            if (advertiseHeadSymref) {
                capabilities.add(GitCapability.SYMREF.withValue("HEAD:" + headTarget));
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

    private static String effectiveHeadTarget(
            NativeGitRepository repository,
            Map<String, String> refs) {
        String defaultHead = repository.defaultHead();
        if (refs.containsKey(defaultHead)) {
            return defaultHead;
        }
        List<String> branchRefs = new ArrayList<>();
        for (String refName : refs.keySet()) {
            if (refName.startsWith("refs/heads/")) {
                branchRefs.add(refName);
            }
        }
        branchRefs.sort(String::compareTo);
        if (!branchRefs.isEmpty()) {
            return branchRefs.getFirst();
        }
        return defaultHead;
    }

    private Result<NativeGitRepository> receiveRepository(
            String repositoryName,
            GitNativeRepositoryAccessHook accessHook) {
        Objects.requireNonNull(accessHook, "accessHook");
        requirePublicRepository(repositoryName);
        accessHook.beforeReceive(repositoryName);
        return findOrCreate(repositoryName, accessHook);
    }

    private void requirePublicRepository(String repositoryName) {
        if (!repositoryProvider.isPublicRepositoryName(repositoryName)) {
            throw new GitNativeRepositoryAccessHook.AccessDeniedException(
                    "Repository is not publicly accessible", null);
        }
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

}
