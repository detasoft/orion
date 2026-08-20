package pro.deta.orion.git.nativestorage.pack;

import java.util.List;
import java.util.Optional;

public interface PackPublicationStore {
    PackPublicationStore NONE = ignored -> Optional.empty();

    Optional<PublishedPack> publish(PackPublicationRequest request);

    default List<PublishedPackManifest> publishedPacks() {
        return List.of();
    }

    default Optional<PublishedPackContent> openPublishedPack(
            String packId) {
        return Optional.empty();
    }
}
