package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.util.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GitNativeRepositoryService {
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
