package pro.deta.orion.git.parser.v2.storage.local;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.shared.PackByteSource;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.closeUnreturned;

/** Local pack bytes keyed by an internal ID; all object metadata and visibility belong to the index. */
public final class LocalGitStorage implements GitStorageApi {
    private final Path directory;

    public LocalGitStorage(Path repository) throws IOException {
        directory = repository.toRealPath().resolve("packs");
        Files.createDirectories(directory);
    }

    @Override
    public PackDataStorage newPack(PackId packId) throws IOException {
        PackDataStorage bytes = FilePackDataStorage.open(path(packId), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            parent.force(true);
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(bytes, failure);
            throw failure;
        }
        return bytes;
    }

    @Override
    public <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        R result = null;
        try (PackDataStorage bytes = FilePackDataStorage.open(path(packId), StandardOpenOption.READ)) {
            if (offset < 0 || length < 0 || offset > bytes.size() || length > bytes.size() - offset) {
                throw new EOFException("Invalid stored pack byte range");
            }
            try (BufferedByteInputV2 input = new BufferedByteInputV2(
                    new PackByteSource(bytes, offset, offset + length))) {
                result = Objects.requireNonNull(reader.read(length, input), "reader result");
                return result;
            }
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(result, failure);
            throw failure;
        }
    }

    @Override
    public boolean exists(PackId packId) {
        return Files.isRegularFile(path(packId));
    }

    private Path path(PackId id) {
        return directory.resolve("pack-" + Objects.requireNonNull(id, "packId") + ".data");
    }

    @Override
    public void close() {}
}
