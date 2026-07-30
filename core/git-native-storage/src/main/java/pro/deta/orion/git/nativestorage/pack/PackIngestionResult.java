package pro.deta.orion.git.nativestorage.pack;

import pro.deta.orion.git.nativestorage.object.LooseObjectStore;

import java.util.Objects;

public sealed interface PackIngestionResult {
    record NeedInput() implements PackIngestionResult {
    }

    record Complete(LooseObjectStore quarantine)
            implements PackIngestionResult {
        public Complete {
            Objects.requireNonNull(quarantine, "quarantine");
        }
    }

    record Failed(PackParseException failure)
            implements PackIngestionResult {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
