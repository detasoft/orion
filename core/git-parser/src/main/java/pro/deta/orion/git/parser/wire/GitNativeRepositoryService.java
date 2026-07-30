package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.LsRefsRequest;
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
    private static final List<GitCapability> UPLOAD_PACK_CAPABILITIES = List.of(
            GitCapability.MULTI_ACK_DETAILED,
            GitCapability.THIN_PACK,
            GitCapability.SIDE_BAND_64K,
            GitCapability.OFS_DELTA,
            GitCapability.agent("orion-native"));
    private static final List<GitCapability> RECEIVE_PACK_CAPABILITIES = List.of(
            GitCapability.REPORT_STATUS,
            GitCapability.SIDE_BAND_64K,
            GitCapability.OFS_DELTA,
            GitCapability.objectFormat("sha1"),
            GitCapability.agent("orion-native"));

    private final InMemoryNativeGitRepositoryProvider repositoryProvider;

    public GitNativeRepositoryService(
            InMemoryNativeGitRepositoryProvider repositoryProvider) {
        this.repositoryProvider = Objects.requireNonNull(
                repositoryProvider,
                "repositoryProvider");
    }

    public GitV1Advertisement legacyUploadPackAdvertisement(
            InitialRequestData data) {
        return legacyAdvertisement(
                data,
                UPLOAD_PACK_CAPABILITIES,
                true);
    }

    public GitV1Advertisement legacyReceivePackAdvertisement(
            InitialRequestData data) {
        return legacyAdvertisement(
                data,
                RECEIVE_PACK_CAPABILITIES,
                false);
    }

    public NativePackProducer legacyUploadPack(
            InitialRequestData data,
            NativeFetchRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        return resolveRepository(data.getRepositoryPath())
                .fetch(request);
    }

    public NativePackProducer protocolV2Fetch(
            InitialRequestData data,
            NativeFetchRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        return resolveRepository(data.getRepositoryPath())
                .fetch(request);
    }

    public PackIngestionSession beginLegacyReceivePack(
            InitialRequestData data) {
        Objects.requireNonNull(data, "data");
        return resolveRepository(data.getRepositoryPath())
                .beginPackIngestion(RECEIVE_PACK_LIMITS);
    }

    public GitLsRefsResponse lsRefs(
            InitialRequestData data,
            LsRefsRequest request) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(request, "request");
        NativeGitRepository repository =
                resolveRepository(data.getRepositoryPath());
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

    private GitV1Advertisement legacyAdvertisement(
            InitialRequestData data,
            List<GitCapability> baseCapabilities,
            boolean advertiseHeadSymref) {
        Objects.requireNonNull(data, "data");
        NativeGitRepository repository =
                resolveRepository(data.getRepositoryPath());
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

    private NativeGitRepository resolveRepository(String repositoryPath) {
        String repositoryName = repositoryPath;
        while (repositoryName.startsWith("/")) {
            repositoryName = repositoryName.substring(1);
        }
        Result<NativeGitRepository> result =
                repositoryProvider.findOrCreate(repositoryName);
        return switch (result) {
            case Result.Success<NativeGitRepository> success ->
                    success.value();
            case Result.Failure<NativeGitRepository> failure ->
                    throw new IllegalStateException(
                            failure.getMessage(),
                            failure.throwable());
        };
    }
}
