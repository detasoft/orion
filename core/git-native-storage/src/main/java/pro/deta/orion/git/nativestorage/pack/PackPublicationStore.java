package pro.deta.orion.git.nativestorage.pack;

import java.util.Optional;

public interface PackPublicationStore {
    PackPublicationStore NONE = ignored -> Optional.empty();

    Optional<PublishedPack> publish(PackPublicationRequest request);
}
