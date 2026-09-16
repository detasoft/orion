package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackObjectParser;

import java.io.IOException;
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
        Path indexPath = directory.resolve("incoming.mv");
        Path temporaryPath = directory.resolve("incoming.tmv");
        var base = new ObjectId("1".repeat(40));
        var object = new ObjectId("2".repeat(40));
        var entry = new PackObjectParser.Entry(12, 33, 4, ObjectType.REF_DELTA,
                OptionalLong.empty(), Optional.of(base));
        try (var index = FilePackIndex.create(indexPath, temporaryPath)) {
            index.addEntry(entry);
            assertThat(Files.size(indexPath)).isPositive();
            assertThat(Files.size(temporaryPath)).isPositive();
            assertThat(index.waitingFor(base, 0)).contains(entry);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class);
            assertThat(Files.exists(temporaryPath)).isTrue();
            index.addObject(entry, object, ObjectType.BLOB, 3);
            assertThat(index.nextExternalBase()).contains(base);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class);
            var appended = new PackObjectParser.Entry(64, 65, 3, ObjectType.BLOB,
                    OptionalLong.empty(), Optional.empty());
            index.addEntry(appended);
            index.addObject(appended, base, ObjectType.BLOB, 3);
            index.finish();
            assertThat(Files.exists(temporaryPath)).isFalse();
            assertThat(index.find(object)).contains(entry);
            assertThat(index.find(base)).isPresent();
        }
        Path published = directory.resolve("published.mv");
        Files.move(indexPath, published, StandardCopyOption.ATOMIC_MOVE);
        try (var index = StoredPackIndex.open(published)) {
            assertThat(index.find(object)).contains(entry);
            assertThat(index.find(12)).contains(entry);
            assertThat(index.find(base)).isPresent();
        }
        try (var store = new MVStore.Builder().fileName(published.toString()).readOnly().open()) {
            assertThat(store.getMapNames()).containsExactlyInAnyOrder("entries", "objects");
        }
        assertThat(Files.exists(temporaryPath)).isFalse();
    }

    @Test
    void setupFailurePreservesExistingFilesAndRemovesOnlyTheNewIndex() throws Exception {
        Path indexPath = directory.resolve("incoming.mv");
        Path temporaryPath = directory.resolve("incoming.tmv");
        Files.writeString(temporaryPath, "another attempt");
        assertThatThrownBy(() -> FilePackIndex.create(indexPath, temporaryPath)).isInstanceOf(IOException.class);
        assertThat(Files.exists(indexPath)).isFalse();
        assertThat(Files.readString(temporaryPath)).isEqualTo("another attempt");
    }

    @Test
    void closingAnUnfinishedAttemptReleasesAndDeletesOnlyItsTemporaryStore() throws Exception {
        Path indexPath = directory.resolve("incoming.mv");
        Path temporaryPath = directory.resolve("incoming.tmv");
        var index = FilePackIndex.create(indexPath, temporaryPath);
        index.addEntry(new PackObjectParser.Entry(12, 13, 3, ObjectType.BLOB,
                OptionalLong.empty(), Optional.empty()));
        index.close();
        index.close();
        assertThat(Files.exists(temporaryPath)).isFalse();
        assertThat(Files.exists(indexPath)).isTrue();
        assertThatThrownBy(index::hasUnresolved).isInstanceOf(ClosedChannelException.class);
        Files.delete(indexPath);
    }
}
