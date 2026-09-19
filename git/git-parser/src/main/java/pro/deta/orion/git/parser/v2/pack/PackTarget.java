package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalLong;

public interface PackTarget {
    void append(ByteBuffer source) throws IOException;

    boolean addEntry(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                     OptionalLong baseOffset, Optional<ObjectId> baseId) throws IOException;

    boolean addObject(long offset, ObjectId id, GitObjectType type, long size) throws IOException;

    void discard() throws IOException;
}
