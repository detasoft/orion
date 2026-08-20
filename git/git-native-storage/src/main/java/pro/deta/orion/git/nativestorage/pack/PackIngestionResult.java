package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;

import java.util.Objects;
import java.util.Optional;

public sealed interface PackIngestionResult {
    record NeedInput() implements PackIngestionResult {
    }

    record Complete(
            LooseObjectStore quarantine,
            Optional<PublishedPack> publishedPack)
            implements PackIngestionResult {
        public Complete(LooseObjectStore quarantine) {
            this(quarantine, Optional.empty());
        }

        public Complete {
            Objects.requireNonNull(quarantine, "quarantine");
            publishedPack = Objects.requireNonNull(
                    publishedPack,
                    "publishedPack");
        }
    }

    record Failed(PackParseException failure)
            implements PackIngestionResult {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
