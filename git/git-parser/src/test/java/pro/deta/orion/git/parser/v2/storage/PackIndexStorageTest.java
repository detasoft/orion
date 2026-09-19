package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackUploadIndex;

import java.io.IOException;
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
        var base = new ObjectId("1".repeat(40));
        var object = new ObjectId("2".repeat(40));
        var entry = new IndexedPack.EntryMetadata(12, 33, 4, GitObjectType.REF_DELTA,
                OptionalLong.empty(), Optional.of(base));
        try (IndexedPack pack = IndexedPack.create(staging); PackUploadIndex index = PackUploadIndex.create(pack)) {
            index.addEntry(entry);
            assertThat(Files.size(indexPath)).isPositive();
            assertThat(Files.size(temporaryPath)).isPositive();
            assertThat(index.waitingFor(base, 0)).contains(entry);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class);
            assertThat(Files.exists(temporaryPath)).isTrue();
            index.addObject(entry, object, GitObjectType.BLOB, 3);
            assertThat(index.nextExternalBase()).contains(base);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class);
            var appended = new IndexedPack.EntryMetadata(64, 65, 3, GitObjectType.BLOB,
                    OptionalLong.empty(), Optional.empty());
            index.addEntry(appended);
            index.addObject(appended, base, GitObjectType.BLOB, 3);
            index.finish();
            assertThat(Files.exists(temporaryPath)).isFalse();
            assertThat(pack.find(object)).contains(entry);
            assertThat(pack.find(base)).isPresent();
        }
        Path published = directory.resolve("published.mv");
        Files.move(indexPath, published, StandardCopyOption.ATOMIC_MOVE);
        try (IndexedPack pack = IndexedPack.open(staging.resolve("data.pack"), published)) {
            assertThat(pack.find(object)).contains(entry);
            assertThat(pack.find(12)).contains(entry);
            assertThat(pack.find(base)).isPresent();
        }
        try (var store = new MVStore.Builder().fileName(published.toString()).readOnly().open()) {
            assertThat(store.getMapNames()).containsExactlyInAnyOrder("entries", "objects");
        }
        assertThat(Files.exists(temporaryPath)).isFalse();
    }

    @Test
    void setupFailurePreservesThePackAndAnotherAttemptsTemporaryState() throws Exception {
        Path staging = directory.resolve("pack");
        Path indexPath = staging.resolve("data.mv");
        Path temporaryPath = staging.resolve("data.tmv");
        try (IndexedPack pack = IndexedPack.create(staging)) {
            Files.writeString(temporaryPath, "another attempt");
            assertThatThrownBy(() -> PackUploadIndex.create(pack)).isInstanceOf(IOException.class);
            assertThat(Files.exists(indexPath)).isTrue();
            assertThat(Files.readString(temporaryPath)).isEqualTo("another attempt");
            assertThat(pack.entryCount()).isZero();
        }
    }

    @Test
    void closingAnUnfinishedAttemptReleasesAndDeletesOnlyItsTemporaryStore() throws Exception {
        Path staging = directory.resolve("pack");
        Path indexPath = staging.resolve("data.mv");
        Path temporaryPath = staging.resolve("data.tmv");
        try (IndexedPack pack = IndexedPack.create(staging)) {
            PackUploadIndex index = PackUploadIndex.create(pack);
            index.addEntry(new IndexedPack.EntryMetadata(12, 13, 3, GitObjectType.BLOB,
                    OptionalLong.empty(), Optional.empty()));
            index.close();
            index.close();
            assertThat(Files.exists(temporaryPath)).isFalse();
            assertThat(Files.exists(indexPath)).isTrue();
            assertThat(pack.find(12)).isPresent();
        }
    }
}
