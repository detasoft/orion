package pro.deta.orion.git.parser.v2.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitIndexModificationTest {
    @TempDir
    Path directory;

    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final ObjectId TARGET = new ObjectId("1".repeat(40));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingOwnerRejectsNewAccessButAllowsExistingAccessToApply(boolean local) throws Exception {
        try (GitIndexApi index = index(local)) {
            index.withAccess(reader -> index.withAccess(List.of(update()), writer -> {
                index.close();
                index.close();
                assertThatThrownBy(index::createAccess).hasMessageContaining("closed");
                writer.apply();
                assertThat(reader.snapshotRefs().refs()).containsEntry(MAIN, TARGET);
                return null;
            }));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void helperDiscardsPendingRefsOnSuccessAndFailure(boolean local) throws Exception {
        try (GitIndexApi index = index(local)) {
            AtomicReference<GitIndexAccess> captured = new AtomicReference<>();
            index.withAccess(List.of(update()), access -> {
                captured.set(access);
                return null;
            });
            assertThatThrownBy(() -> captured.get().snapshotRefs()).isInstanceOf(IOException.class);
            IOException failure = new IOException("Operation failed");
            assertThatThrownBy(() -> index.withAccess(List.of(update()), access -> {
                captured.set(access);
                throw failure;
            })).isSameAs(failure);
            assertThatThrownBy(() -> captured.get().snapshotRefs()).isInstanceOf(IOException.class);
            Map<RefId, ObjectId> refs = index.withAccess(access -> access.snapshotRefs().refs());
            assertThat(refs).isEmpty();
        }
    }

    @Test
    void lastDiscardReleasesTheFileAndAllowsReopening() throws Exception {
        try (LocalGitIndex index = new LocalGitIndex(directory)) {
            index.withAccess(List.of(update()), access -> {
                access.apply();
                return null;
            });
            assertFileUnlocked();
            index.withAccess(access -> {
                assertThat(access.snapshotRefs().refs()).containsEntry(MAIN, TARGET);
                return null;
            });
            assertFileUnlocked();
        }
    }

    @Test
    void interruptedCleanupReleasesTheAccessAndPreservesTheInterrupt() throws Exception {
        try (LocalGitIndex index = new LocalGitIndex(directory)) {
            index.withAccess(access -> {
                Thread.currentThread().interrupt();
                try {
                    access.discard();
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } finally {
                    Thread.interrupted();
                }
                return null;
            });
            assertFileUnlocked();
        }
    }

    private GitIndexApi index(boolean local) throws IOException {
        return local ? new LocalGitIndex(directory) : new InMemoryIndex();
    }

    private RefUpdate update() {
        return new RefUpdate(MAIN, Optional.empty(), Optional.of(TARGET));
    }

    private void assertFileUnlocked() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("refs.mv"), StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            assertThat(lock).isNotNull();
        }
    }
}
