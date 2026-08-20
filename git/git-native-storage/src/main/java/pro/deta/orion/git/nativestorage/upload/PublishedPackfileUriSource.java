package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.pack.PublishedPackManifest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Selects reusable published packs for packfile-URI responses. Only
 * self-contained packs are advertised because a client downloading a pack URI
 * cannot rely on repository-local external delta bases that made a thin
 * receive-pack valid on the server.
 */
public final class PublishedPackfileUriSource
        implements NativePackfileUriSource {
    private final NativeGitRepository repository;
    private final Function<String, String> packUriBuilder;

    public PublishedPackfileUriSource(
            NativeGitRepository repository,
            Function<String, String> packUriBuilder) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository");
        this.packUriBuilder = Objects.requireNonNull(
                packUriBuilder,
                "packUriBuilder");
    }

    @Override
    public NativePackfileUriSelection select(
            Set<GitObjectId> objectIds,
            Set<String> acceptedProtocols) {
        Objects.requireNonNull(objectIds, "objectIds");
        Objects.requireNonNull(acceptedProtocols, "acceptedProtocols");
        if (objectIds.isEmpty() || acceptedProtocols.isEmpty()) {
            return NativePackfileUriSelection.empty();
        }
        Set<GitObjectId> remaining = new LinkedHashSet<>(objectIds);
        Set<GitObjectId> covered = new LinkedHashSet<>();
        List<NativePackfileUri> packfileUris = new ArrayList<>();
        for (PublishedPackManifest manifest : repository.publishedPacks()) {
            if (!manifest.selfContained()
                    || !containsAny(remaining, manifest.objectIds())) {
                continue;
            }
            NativePackfileUri packfileUri = new NativePackfileUri(
                    manifest.packChecksum(),
                    packUriBuilder.apply(manifest.packId()));
            if (!acceptedProtocols.contains(packfileUri.protocol())) {
                continue;
            }
            packfileUris.add(packfileUri);
            covered.addAll(intersection(objectIds, manifest.objectIds()));
            remaining.removeAll(manifest.objectIds());
            if (remaining.isEmpty()) {
                break;
            }
        }
        return new NativePackfileUriSelection(packfileUris, covered);
    }

    private static boolean containsAny(
            Set<GitObjectId> objectIds,
            Set<GitObjectId> candidates) {
        for (GitObjectId candidate : candidates) {
            if (objectIds.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Set<GitObjectId> intersection(
            Set<GitObjectId> objectIds,
            Set<GitObjectId> candidates) {
        Set<GitObjectId> result = new LinkedHashSet<>();
        for (GitObjectId candidate : candidates) {
            if (objectIds.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
