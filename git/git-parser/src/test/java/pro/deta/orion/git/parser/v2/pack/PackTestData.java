package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;

public final class PackTestData {
    private PackTestData() {}

    public static byte[] compressed(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(output)) {
            zlib.write(content);
        }
        return output.toByteArray();
    }

    public static byte[] entry(GitObjectType type, byte[] content) throws IOException {
        return join(PackEntryWriter.objectHeader(type, content.length), compressed(content));
    }

    public static byte[] blob(byte[] content) throws IOException {
        return entry(GitObjectType.BLOB, content);
    }

    public static byte[] delta(ObjectId base, byte[] instructions) throws IOException {
        return join(PackEntryWriter.objectHeader(GitObjectType.REF_DELTA, instructions.length), base.toBytes(),
                compressed(instructions));
    }

    public static byte[] pack(byte[]... entries) {
        return pack(2, entries);
    }

    public static byte[] pack(int version, byte[]... entries) {
        byte[] body = join(ByteBuffer.allocate(12).putInt(0x5041434b).putInt(version).putInt(entries.length).array(),
                join(entries));
        return join(body, GitHashAlgorithm.SHA1.newDigest().digest(body));
    }

    public static byte[] join(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    public static ObjectId objectId(GitObjectType type, byte[] content) {
        MessageDigest hash = GitHashAlgorithm.SHA1.newDigest();
        hash.update((type.name().toLowerCase(Locale.ROOT) + " " + content.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        return new ObjectId(hash.digest(content));
    }

    public static GitIndexAccess inspect(byte[] bytes) throws IOException {
        GitIndexAccess index = new InMemoryIndex().createAccess();
        try (GitStorageApi storage = new InMemoryStorage()) {
            publish(bytes, storage, index);
        }
        return index;
    }

    public static PackMetadata ingest(byte[] bytes, GitStorageApi storage, GitIndexAccess index) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
             PackIngestor ingestor = new PackIngestor(input, storage, index)) {
            return ingestor.ingest();
        }
    }

    public static PackMetadata publish(byte[] bytes, GitStorageApi storage, GitIndexAccess index) throws IOException {
        return index.publishIndex(ingest(bytes, storage, index));
    }

    public static ObjectId store(GitStorageApi storage, GitIndexAccess index,
                                 GitObjectType type, byte[] content) throws IOException {
        publish(pack(entry(type, content)), storage, index);
        return objectId(type, content);
    }

    public static ObjectId storeDelta(GitStorageApi storage, GitIndexAccess index,
                                      GitObjectType type, byte[] base, byte[] instructions, byte[] result)
            throws IOException {
        publish(pack(entry(type, base), delta(objectId(type, base), instructions)), storage, index);
        return objectId(type, result);
    }

    public static byte[] bytes(PackMetadata pack, GitStorageApi storage, GitIndexAccess index) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(output), pack.objectCount())) {
            writer.writeObjects(storage, index.objects(pack.packId()));
            if (!writer.finish().equals(pack.packChecksum())) {
                throw new IOException("Export checksum differs from metadata");
            }
        }
        return output.toByteArray();
    }
}
