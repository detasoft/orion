package pro.deta.orion.git.parser.v2.pack.mv;

import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Encodes object IDs as twenty raw bytes, ordered by unsigned byte value for disk index lookups. */
final class ObjectIdDataType extends BasicDataType<ObjectId> {
    static final ObjectIdDataType INSTANCE = new ObjectIdDataType();

    @Override
    public int compare(ObjectId left, ObjectId right) {
        return Arrays.compareUnsigned(left.toBytes(), right.toBytes());
    }

    @Override
    public int getMemory(ObjectId value) {
        return 64;
    }

    @Override
    public void write(WriteBuffer buffer, ObjectId value) {
        buffer.put(value.toBytes());
    }

    @Override
    public ObjectId read(ByteBuffer buffer) {
        byte[] bytes = new byte[20];
        buffer.get(bytes);
        return new ObjectId(bytes);
    }

    @Override
    public ObjectId[] createStorage(int size) {
        return new ObjectId[size];
    }
}
