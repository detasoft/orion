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

    public static MutableIndexedPack ingest(byte[] bytes, MutableIndexedPack target) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            return ingest(input, target);
        }
    }

    public static MutableIndexedPack ingest(IndexedPack source, MutableIndexedPack target) throws IOException {
        try (BufferedByteInputV2 input = source.input()) {
            return ingest(input, target);
        }
    }

    private static MutableIndexedPack ingest(BufferedByteInputV2 input, MutableIndexedPack target)
            throws IOException {
        try (PackReader reader = new PackReader(input)) {
            while (true) {
                switch (reader.next()) {
                    case PackReadStep.Bytes bytes -> target.append(bytes.data());
                    case PackReadStep.EntryEnd end -> {
                        PackEntry entry = end.metadata();
                        target.addEntry(entry.offset(), entry.dataOffset(), entry.inflatedSize(), entry.type(),
                                entry.baseOffset(), entry.baseId());
                        if (end.objectId().isPresent()) {
                            target.addObject(entry.offset(), end.objectId().orElseThrow(), entry.type(),
                                    entry.inflatedSize());
                        }
                    }
                    case PackReadStep.End end -> {
                        target.setId(end.id());
                        return target;
                    }
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                target.discard();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static ObjectId store(GitStorageApi storage,
                                 GitObjectType type, byte[] content) throws IOException {
        MutableIndexedPack target = ingest(pack(entry(type, content)), storage.newPack());
        new GitPackObjectResolver(target, storage).complete();
        storage.persist(target);
        return objectId(type, content);
    }

    public static ObjectId storeDelta(GitStorageApi storage,
                                      GitObjectType type, byte[] base, byte[] instructions, byte[] result)
            throws IOException {
        byte[] full = entry(type, base);
        ObjectId id = objectId(type, result);
        MutableIndexedPack target = ingest(pack(full, delta(objectId(type, base), instructions)), storage.newPack());
        new GitPackObjectResolver(target, storage).complete();
        storage.persist(target);
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
