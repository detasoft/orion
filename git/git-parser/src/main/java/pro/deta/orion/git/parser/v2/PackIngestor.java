package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.data.PackScanIndex;

import java.io.IOException;

/**
 * Resolves objects in an already received and physically checked pack for one calling operation.
 * PushCommand creates and closes this ingestor and supplies its repository storage access during wiring.
 * The input PackScanIndex carries the verified PackId, entry offsets, encoding, sizes, and CRC32 values;
 * resolvePack uses that metadata rather than repeating network reception and physical pack scanning.
 *
 * <p>Resolution opens original pack bytes through GitStorageApi.openPack(index.packId()), reconstructs internal
 * delta dependencies, and reads external bases through GitStorageApi.readObject. It computes resolved ObjectIds
 * and records the final object index and confirmed externalBaseIds through GitStorageApi before success.
 * The storage operation accepting that result remains to be defined. Resolution itself does not publish the
 * pack, update refs, or perform push access and ancestry policy checks.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code resolvePack(PackScanIndex index)} - complete object resolution and prepare the pack for publication.</li>
 *   <li>{@code close()} - release owned pack/object reads and resolution resources on every operation outcome.</li>
 * </ul>
 * Methods remain placeholders. Input-byte reception and temporary reception identity belong to storage;
 * this ingestor does not own the transport input or the repository-wide GitStorageApi.
 */
public final class PackIngestor implements AutoCloseable {
    public void resolvePack(PackScanIndex index) throws IOException {
        throw new UnsupportedOperationException("Pack resolution is not implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Pack resolution cleanup is not implemented");
    }
}
