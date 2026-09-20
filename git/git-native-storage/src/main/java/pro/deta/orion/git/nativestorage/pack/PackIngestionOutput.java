package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class PackIngestionOutput implements BufferedByteOutput, AutoCloseable {
    private final GitStorageApi storage;
    private final Path temporary;
    private final OutputStream output;
    private boolean finished;

    public PackIngestionOutput(GitStorageApi storage) throws IOException {
        this.storage = Objects.requireNonNull(storage, "storage");
        temporary = Files.createTempFile("orion-fetch-", ".pack");
        try {
            output = Files.newOutputStream(temporary);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        requireWritable();
        output.write(bytes, offset, length);
    }

    @Override
    public void write(ByteBuf bytes) throws IOException {
        requireWritable();
        bytes.getBytes(bytes.readerIndex(), output, bytes.readableBytes());
    }

    @Override
    public void flush() throws IOException {
        requireWritable();
        output.flush();
    }

    public IndexedPack complete() throws IOException {
        requireWritable();
        finished = true;
        output.close();
        IndexedPack pack = null;
        try (BufferedByteInputV2 input = new BufferedByteInputV2(Files.newInputStream(temporary));
             PackIngestor ingestor = new PackIngestor(input, storage.newPack())) {
            pack = ingestor.ingest();
            if (input.buffer() != null) {
                throw new IOException("Unexpected bytes after pack trailer");
            }
            new GitPackObjectResolver(pack, storage).complete();
        } catch (IOException | RuntimeException | Error failure) {
            if (pack != null) {
                try {
                    pack.discard();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        return pack;
    }

    @Override
    public void close() throws IOException {
        finished = true;
        try {
            output.close();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void requireWritable() throws IOException {
        if (finished) {
            throw new IOException("Pack output is closed or completed");
        }
    }
}
