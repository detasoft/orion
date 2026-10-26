package pro.deta.orion.git.fileapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.test.util.OrionTestRuntime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFileBindingTest {
    @RegisterExtension
    final OrionTestRuntime runtime = new OrionTestRuntime();

    private static final String REF = "refs/heads/main";
    private static final GitCommitMetadata METADATA = new GitCommitMetadata(GitCommitAuthor.EMPTY, "update");

    @Test
    void readsPinnedRevisionsAndRejectsStalePublication() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        NativeGitRepositoryProvider provider = new NativeGitRepositoryProvider(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(runtime.events())) {
            @Override
            public pro.deta.orion.util.Result<NativeGitRepository> openForRead(String name) {
                opens.incrementAndGet();
                return super.openForRead(name);
            }
        };
        runtime.own(provider);
        provider.create("repository").valueOrFailure("create repository");
        GitFile file = new GitFile(provider, "repository", REF, "config/material");
        assertThat(file.revision()).isEmpty();
        CommitId first = file.write(null, bytes("first"), METADATA);
        CommitId second = file.write(first, bytes("second"), METADATA);
        int refreshed = opens.get();
        assertThat(file.read(first)).isEqualTo(bytes("first"));
        assertThat(file.read(second)).isEqualTo(bytes("second"));
        assertThat(opens).hasValue(refreshed);
        assertThatThrownBy(() -> file.write(first, bytes("stale"), METADATA))
                .isInstanceOf(GitRefConflictException.class);
        assertThat(file.revision()).contains(second);
    }

    @Test
    void notifiesOnlyForThisFileIncludingDeletionAndDetachesOnClose() throws Exception {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(runtime.events());
        runtime.own(provider);
        NativeGitRepository repository = provider.create("repository").valueOrFailure("create repository");
        GitFile file = new GitFile(provider, "repository", REF, "material");
        CommitId first = file.write(null, bytes("first"), METADATA);
        runtime.awaitEvents();
        List<CommitId> changes = new CopyOnWriteArrayList<>();
        GitFileEvents events = new GitFileEvents(file);
        runtime.own(new GitFileEvents(file));
        runtime.own(runtime.events().subscribe(GitFileChanged.class, event -> {
            if (events.matches(event)) changes.add(event.revision());
        }));
        try {
            repository.files().withAccess(REF, METADATA, access -> {
                access.write("other", bytes("unrelated"));
                access.apply();
                return null;
            });
            repository.files().withAccess("refs/heads/other", METADATA, access -> {
                access.write("material", bytes("other branch"));
                access.apply();
                return null;
            });
            NativeGitRepository other = provider.create("other").valueOrFailure("create other repository");
            other.files().withAccess(REF, METADATA, access -> {
                access.write("material", bytes("other repository"));
                access.apply();
                return null;
            });
            runtime.awaitEvents();
            assertThat(changes).isEmpty();
            assertThat(file.isCurrent(first)).isTrue();
            CommitId second = file.write(first, bytes("second"), METADATA);
            assertThat(file.isCurrent(first)).isFalse();
            CommitId deleted = repository.files().withAccess(REF, METADATA, access -> {
                access.delete("material");
                CommitId revision = new CommitId(access.refUpdates().getFirst().newId().orElseThrow().toHex());
                access.apply();
                return revision;
            });
            runtime.awaitEvents();
            assertThat(changes).containsExactly(second, deleted);
            assertThat(file.read(first)).isEqualTo(bytes("first"));
            events.close();
            file.write(deleted, bytes("restored"), METADATA);
            runtime.awaitEvents();
            assertThat(changes).containsExactly(second, deleted);
        } finally {
            events.close();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
