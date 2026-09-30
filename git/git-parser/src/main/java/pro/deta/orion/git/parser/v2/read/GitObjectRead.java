package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@FunctionalInterface
public interface GitObjectRead<R> {
    R read(GitObjectType type, long inflatedSize, Optional<ObjectId> baseId,
            BufferedByteInputV2 source) throws IOException;

    static <R> Optional<R> read(GitStorageApi storage, GitIndexApi index, ObjectId id,
                                GitObjectRead<R> reader) throws IOException {
        List<IndexedObject> locations = index.locations(id);
        return locations.isEmpty() ? Optional.empty()
                : Optional.of(read(storage, locations.getFirst(), reader));
    }

    static <R> R read(GitStorageApi storage, IndexedObject object, GitObjectRead<R> reader) throws IOException {
        GitObjectType type = object.delta().isPresent() ? GitObjectType.REF_DELTA : object.type();
        long size = object.delta().map(IndexedObject.Delta::instructionSize).orElse(object.objectSize());
        Optional<ObjectId> base = object.delta().map(IndexedObject.Delta::baseId);
        return storage.readPack(object.packId(), object.packOffset(), object.compressedSize(),
                (length, source) -> reader.read(type, size, base, source));
    }

    static boolean exists(GitStorageApi storage, GitIndexApi index, ObjectId id) throws IOException {
        for (IndexedObject object : index.locations(id)) {
            if (storage.exists(object.packId())) {
                return true;
            }
        }
        return false;
    }
}
