package pro.deta.orion.git.sync;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.pack.PackIngestionOutput;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartHttpGitRemoteGatewayClosureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAllHeadsBeforeTrackingOrLivePublicationWhenOneTreeIsMissing(boolean attach) throws Exception {
        Entry tree = new Entry(GitObjectType.TREE, new byte[0]);
        Entry old = commit(tree.id(), "old");
        Entry complete = commit(tree.id(), "new");
        Entry incomplete = commit("f".repeat(40), "missing tree");
        try (NativeGitRepository repository = repository()) {
            persist(repository, pack(tree, old));
            repository.updateRef("refs/remotes/upstream/main", "0".repeat(40), old.id());
            repository.updateRef("refs/remotes/upstream/release", "0".repeat(40), old.id());
            Map<String, String> before = repository.refs();
            try (SmartHttpGitRemoteGateway gateway = gateway(Map.of(
                    "refs/heads/main", complete.id(), "refs/heads/release", incomplete.id(),
                    "refs/heads/alias", complete.id()), pack(complete, incomplete))) {
                assertThatThrownBy(() -> {
                    if (attach) {
                        new GitAttachment(repository, gateway, new GitCommitRelationships() {
                            @Override
                            public boolean isAncestor(String ancestor, String descendant) {
                                throw new AssertionError("incomplete fetch must not reach planning");
                            }

                            @Override
                            public Optional<String> mergeBase(String first, String second) {
                                throw new AssertionError("incomplete fetch must not reach planning");
                            }
                        }).attach();
                    } else {
                        gateway.fetchHeads(repository);
                    }
                }).isInstanceOf(GitRemoteException.class)
                        .hasMessage("Remote Git complete object validation failed");
                assertThat(repository.refs()).containsExactlyInAnyOrderEntriesOf(before);
            }
        }
    }

    @Test
    void validatesAgainstExistingLocalObjectsAndPublishesEveryHead() throws Exception {
        Entry tree = new Entry(GitObjectType.TREE, new byte[0]);
        Entry commit = commit(tree.id(), "local tree");
        try (NativeGitRepository repository = repository()) {
            persist(repository, pack(tree));
            Map<String, String> heads = Map.of("refs/heads/main", commit.id(), "refs/heads/alias", commit.id());
            try (SmartHttpGitRemoteGateway gateway = gateway(heads, pack(commit))) {
                assertThat(gateway.fetchHeads(repository).heads()).isEqualTo(heads);
                assertThat(repository.refs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "refs/remotes/upstream/main", commit.id(), "refs/remotes/upstream/alias", commit.id()));
            }
        }
    }

    private static NativeGitRepository repository() {
        return new NativeGitRepository("project", new InMemoryStorage(), "refs/heads/main");
    }

    private static Entry commit(String tree, String message) {
        return new Entry(GitObjectType.COMMIT, ("tree " + tree + "\n\n" + message + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void persist(NativeGitRepository repository, byte[] bytes) throws IOException {
        try (PackIngestionOutput output = new PackIngestionOutput(repository.storage())) {
            output.write(bytes);
            repository.storage().persist(output.complete());
        }
    }

    private static byte[] pack(Entry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), entries.length)) {
            for (Entry entry : entries) {
                try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(entry.content()))) {
                    writer.writeObject(entry.type(), entry.content().length, input);
                }
            }
            writer.finish();
        }
        return bytes.toByteArray();
    }

    private static SmartHttpGitRemoteGateway gateway(Map<String, String> heads, byte[] pack) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        BufferedByteOutput output = new OutputStreamBufferedByteOutput(response);
        for (Map.Entry<String, String> head : heads.entrySet()) {
            new GitPktLine.Data((head.getValue() + " " + head.getKey() + "\n")
                    .getBytes(StandardCharsets.US_ASCII)).writeTo(output);
        }
        GitPktLine.Control.FLUSH.writeTo(output);
        new GitPktLine.Data("NAK\n".getBytes(StandardCharsets.US_ASCII)).writeTo(output);
        output.write(pack);
        byte[] bytes = response.toByteArray();
        GitClientTransport transport = (service, uri, options) -> new GitClientTransportSession() {
            private final BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
            private final BufferedByteOutput sink =
                    new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream());

            @Override
            public BufferedByteInputV2 input() {
                return input;
            }

            @Override
            public BufferedByteOutput output() {
                return sink;
            }

            @Override
            public void close() throws IOException {
                input.close();
            }
        };
        return new SmartHttpGitRemoteGateway(new GitRemoteConnection(URI.create("git://upstream/repository"),
                GitClientOptions.defaults(), new GitUploadPackClient(transport),
                new GitReceivePackClient(transport), () -> { }));
    }

    private record Entry(GitObjectType type, byte[] content) {
        private String id() {
            try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
                return formatter.idFor(type == GitObjectType.TREE ? Constants.OBJ_TREE : Constants.OBJ_COMMIT,
                        content).name();
            }
        }
    }
}
