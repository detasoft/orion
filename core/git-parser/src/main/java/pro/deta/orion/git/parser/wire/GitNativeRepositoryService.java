package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
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
    private static final List<GitCapability> UPLOAD_PACK_CAPABILITIES = List.of(
            GitCapability.MULTI_ACK_DETAILED,
            GitCapability.THIN_PACK,
            GitCapability.SIDE_BAND_64K,
            GitCapability.OFS_DELTA,
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
        Objects.requireNonNull(data, "data");
        NativeGitRepository repository =
                resolveRepository(data.getRepositoryPath());
        Map<String, String> refs = repository.refs();
        List<GitAdvertisedRef> advertisedRefs = new ArrayList<>();
        String headTarget = repository.defaultHead();
        String headObjectId = refs.get(headTarget);
        List<GitCapability> capabilities =
                new ArrayList<>(UPLOAD_PACK_CAPABILITIES);
        if (headObjectId != null) {
            advertisedRefs.add(
                    GitAdvertisedRef.direct(headObjectId, "HEAD"));
            capabilities.add(GitCapability.symref("HEAD", headTarget));
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
