package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public sealed interface PackIngestionResult {
    record NeedInput() implements PackIngestionResult {
    }

    /**
     * Retains received bytes after the ingestion session closes. Empty bytes represent object publication
     * without an incoming pack, including ref-only changes to objects already present in the repository.
     */
    record Complete(
            LooseObjectStore quarantine,
            Optional<PublishedPack> publishedPack,
            byte[] packBytes,
            Set<GitObjectId> externalBaseIds)
            implements PackIngestionResult {
        public Complete(LooseObjectStore quarantine) {
            this(quarantine, Optional.empty(), new byte[0], Set.of());
        }

        public Complete {
            Objects.requireNonNull(quarantine, "quarantine");
            publishedPack = Objects.requireNonNull(
                    publishedPack,
                    "publishedPack");
            packBytes = Objects.requireNonNull(packBytes, "packBytes").clone();
            externalBaseIds = Set.copyOf(externalBaseIds);
        }

        @Override
        public byte[] packBytes() {
            return packBytes.clone();
        }
    }

    record Failed(PackParseException failure)
            implements PackIngestionResult {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
