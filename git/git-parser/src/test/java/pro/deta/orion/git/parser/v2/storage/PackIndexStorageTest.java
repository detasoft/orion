package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.MutableIndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackEntry;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.storage.local.LocalIndexedPack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackIndexStorageTest {
    @TempDir
    Path directory;

    @Test
    void writesPermanentRecordsDuringProcessingAndMovesTheFinishedIndexWithoutTemporaryState() throws Exception {
        Path staging = directory.resolve("pack");
        Path indexPath = staging.resolve("data.mv");
        Path temporaryPath = staging.resolve("data.tmv");
        ObjectId base = new ObjectId("1".repeat(40));
        ObjectId object = new ObjectId("2".repeat(40));
        PackEntry entry = new PackEntry(12, 33, 4, GitObjectType.REF_DELTA,
                OptionalLong.empty(), Optional.of(base));
        try (MutableIndexedPack pack = LocalIndexedPack.create(staging)) {
            pack.append(ByteBuffer.wrap(PackTestData.pack()));
            pack.addEntry(entry);
            assertThat(pack.hasUnresolved()).isTrue();
            assertThat(Files.size(indexPath)).isPositive();
            assertThat(Files.size(temporaryPath)).isPositive();
            assertThat(pack.waitingFor(base, 0)).contains(entry);
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class);
            assertThat(Files.exists(temporaryPath)).isTrue();
            pack.addObject(entry.offset(), object, GitObjectType.BLOB, 3);
            assertThat(pack.nextExternalBase()).contains(base);
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class);
            PackEntry appended = new PackEntry(64, 65, 3, GitObjectType.BLOB,
                    OptionalLong.empty(), Optional.empty());
            pack.addEntry(appended);
            pack.addObject(appended.offset(), base, GitObjectType.BLOB, 3);
            pack.finish(12);
            assertThat(Files.exists(temporaryPath)).isFalse();
            assertThat(pack.find(object)).contains(entry);
            assertThat(pack.find(base)).isPresent();
        }
        Path published = directory.resolve("published.mv");
        Files.move(indexPath, published, StandardCopyOption.ATOMIC_MOVE);
        try (IndexedPack pack = LocalIndexedPack.open(staging.resolve("data.pack"), published)) {
            assertThat(pack.find(object)).contains(entry);
            assertThat(pack.find(12)).contains(entry);
            assertThat(pack.find(base)).isPresent();
        }
        try (MVStore store = new MVStore.Builder().fileName(published.toString()).readOnly().open()) {
            assertThat(store.getMapNames()).containsExactlyInAnyOrder("entries", "objects");
        }
        assertThat(Files.exists(temporaryPath)).isFalse();
    }

    @Test
    void setupFailurePreservesThePackAndAnotherAttemptsTemporaryState() throws Exception {
        Path staging = directory.resolve("pack");
        Path indexPath = staging.resolve("data.mv");
        Path temporaryPath = staging.resolve("data.tmv");
        try (MutableIndexedPack pack = LocalIndexedPack.create(staging)) {
            Files.writeString(temporaryPath, "another attempt");
            assertThatThrownBy(pack::hasUnresolved).isInstanceOf(IOException.class);
            assertThat(Files.exists(indexPath)).isTrue();
            assertThat(Files.readString(temporaryPath)).isEqualTo("another attempt");
            assertThat(pack.entryCount()).isZero();
        }
    }

    @Test
    void closingUnfinishedPackReleasesTemporaryStoreAndPreservesPermanentFiles() throws Exception {
        Path staging = directory.resolve("pack");
        Path indexPath = staging.resolve("data.mv");
        Path temporaryPath = staging.resolve("data.tmv");
        try (MutableIndexedPack pack = LocalIndexedPack.create(staging)) {
            pack.addEntry(new PackEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty()));
            assertThat(pack.hasUnresolved()).isTrue();
            assertThat(Files.exists(temporaryPath)).isTrue();
            pack.close();
            pack.close();
            assertThat(Files.exists(temporaryPath)).isFalse();
            assertThat(Files.exists(indexPath)).isTrue();
            assertThatThrownBy(pack::hasUnresolved).isInstanceOf(ClosedChannelException.class);
        }
    }

    @Test
    void discardsPackWithActiveDependenciesAndRemovesItsDirectory() throws Exception {
        Path staging = directory.resolve("pack");
        try (MutableIndexedPack pack = LocalIndexedPack.create(staging)) {
            pack.addEntry(new PackEntry(12, 13, 3, GitObjectType.BLOB, OptionalLong.empty(), Optional.empty()));
            assertThat(pack.hasUnresolved()).isTrue();
            assertThat(Files.exists(staging.resolve("data.tmv"))).isTrue();
            pack.discard();
            assertThat(Files.exists(staging)).isFalse();
            assertThatThrownBy(pack::size).isInstanceOf(ClosedChannelException.class);
        }
    }

}
