package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

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
        return join(PackWriter.objectHeader(type, content.length), compressed(content));
    }

    public static byte[] blob(byte[] content) throws IOException {
        return entry(GitObjectType.BLOB, content);
    }

    public static byte[] delta(ObjectId base, byte[] instructions) throws IOException {
        return join(PackWriter.objectHeader(GitObjectType.REF_DELTA, instructions.length), base.toBytes(),
                compressed(instructions));
    }

    public static byte[] pack(byte[]... entries) {
        byte[] body = join(ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(entries.length).array(),
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

    public static IndexedPack ingest(byte[] bytes, IndexedPack target) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
             PackIngestor ingestor = new PackIngestor(input, target)) {
            return ingestor.ingest();
        }
    }

    public static ObjectId store(GitStorageApi storage,
                                 GitObjectType type, byte[] content) throws IOException {
        storage.persist(ingest(pack(entry(type, content)), storage.newPack()));
        return objectId(type, content);
    }

    public static ObjectId storeDelta(GitStorageApi storage,
                                      GitObjectType type, byte[] base, byte[] instructions, byte[] result)
            throws IOException {
        byte[] full = entry(type, base);
        ObjectId id = objectId(type, result);
        try (IndexedPack target = ingest(pack(full, delta(objectId(type, base), instructions)), storage.newPack())) {
            new GitPackObjectResolver(target, storage).complete();
            storage.persist(target);
        }
        return id;
    }

    public static byte[] bytes(IndexedPack pack) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(pack.size()));
        while (bytes.hasRemaining()) {
            if (pack.read(bytes.position(), bytes) <= 0) {
                throw new IOException("Truncated test pack");
            }
        }
        return bytes.array();
    }
}
