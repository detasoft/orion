package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.PackEntry;

import java.util.Optional;

public record PackObjectLocation(ObjectId objectId, PackId packId, PackEntry entry,
                                 long end, Optional<ObjectId> baseId) {
}
