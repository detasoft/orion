package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefsCommandTest implements BufferedByteInputV2.Source {
    @TempDir
    Path repository;
    private ByteBuffer source;

    @Test
    void listsRefsWithSymbolicHeadAndFiltersByAnyRequestedPrefix() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            ObjectId commit = publish(storage, index, GitObjectType.COMMIT, "tree " + "0".repeat(40) + "\n\nmessage\n");
            addRef(index, "refs/heads/main", commit);
            addRef(index, "refs/heads/ветка", commit);
            addRef(index, "refs/tags/lightweight", commit);
            assertThat(execute(storage, index, "symrefs")).containsExactly(
                    commit + " HEAD symref-target:refs/heads/main",
                    commit + " refs/heads/main", commit + " refs/heads/ветка", commit + " refs/tags/lightweight");
            assertThat(execute(storage, index, "ref-prefix HEAD", "ref-prefix refs/heads/вет"))
                    .containsExactly(commit + " HEAD", commit + " refs/heads/ветка");
            assertThat(execute(storage, index, "ref-prefix absent")).isEmpty();
            assertThat(execute(storage, index, "ref-prefix ")).hasSize(4);
        }
    }

    @Test
    void writesEveryRefAndFlushesAResponseLargerThanTheOutputBuffer() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            ObjectId commit = publish(storage, index, GitObjectType.COMMIT, "tree " + "0".repeat(40) + "\n\nmessage\n");
            List<RefUpdate> updates = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int number = 0; number < 2_000; number++) {
                RefId ref = new RefId("refs/heads/branch-%04d".formatted(number));
                updates.add(new RefUpdate(ref, Optional.empty(), Optional.of(commit)));
                expected.add(commit + " " + ref.value());
            }
            assertThat(index.updateRefs(updates, true)).hasSize(updates.size())
                    .allSatisfy(result -> assertThat(result.status()).isEqualTo(RefUpdateResult.Status.APPLIED));

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (BufferedByteInputV2 input = input("0000".getBytes(StandardCharsets.US_ASCII))) {
                OutputStreamBufferedByteOutput output = new OutputStreamBufferedByteOutput(
                        new BufferedOutputStream(response, 64 * 1024));
                command(storage, index).action(new GitProtocolContext(input, output, GitProtocolVersion.V2, GitTransport.SSH));
            }

            assertThat(response.size()).isGreaterThan(64 * 1024);
            try (BufferedByteInputV2 input = input(response.toByteArray())) {
                for (String line : expected) {
                    GitPktLine packet = GitPktLine.readNextFrom(input).orElseThrow();
                    assertThat(packet).isInstanceOf(GitPktLine.Data.class);
                    assertThat(((GitPktLine.Data) packet).text()).isEqualTo(line);
                }
                assertThat(GitPktLine.readNextFrom(input)).contains(GitPktLine.Control.FLUSH);
                assertThat(GitPktLine.readNextFrom(input)).isEmpty();
            }
        }
    }

    @Test
    void handlesUnbornAndDetachedHead() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            assertThat(execute(storage, index)).isEmpty();
            assertThat(execute(storage, index, "symrefs")).isEmpty();
            assertThat(execute(storage, index, "unborn")).isEmpty();
            assertThat(execute(storage, index, "unborn", "symrefs"))
                    .containsExactly("unborn HEAD symref-target:refs/heads/main");
            assertThat(execute(storage, index, "unborn", "symrefs", "ref-prefix refs/")).isEmpty();
            ObjectId commit = publish(storage, index, GitObjectType.COMMIT, "tree " + "0".repeat(40) + "\n\nmessage\n");
            index.updateHead(new Head.Detached(new CommitId(commit.toBytes())));
            assertThat(execute(storage, index, "symrefs", "unborn")).containsExactly(commit + " HEAD");
        }
    }

    @Test
    void peelsNestedTagsIncludingRefsOutsideTheTagsNamespace() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            ObjectId blob = publish(storage, index, GitObjectType.BLOB, "content");
            ObjectId inner = publish(storage, index, GitObjectType.TAG, tag(blob, "blob", "inner"));
            ObjectId outer = publish(storage, index, GitObjectType.TAG, tag(inner, "tag", "outer"));
            addRef(index, "refs/tags/nested", outer);
            addRef(index, "refs/tags/lightweight", blob);
            addRef(index, "refs/custom/tag", inner);
            index.updateHead(new Head.Symbolic(new RefId("refs/custom/tag")));
            assertThat(execute(storage, index, "peel", "symrefs")).containsExactly(
                    inner + " HEAD symref-target:refs/custom/tag peeled:" + blob,
                    inner + " refs/custom/tag peeled:" + blob,
                    blob + " refs/tags/lightweight", outer + " refs/tags/nested peeled:" + blob);
            assertThat(execute(storage, index, "ref-prefix refs/tags/nested"))
                    .containsExactly(outer + " refs/tags/nested");
        }
    }

    @Test
    void consumesOnlyThisRequestAndWritesNothingBeforeItsFlush() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
            GitPktLine.Control.FLUSH.writeTo(request);
            new GitPktLine.Data("command=fetch\n".getBytes(StandardCharsets.UTF_8)).writeTo(request);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
                command(storage, index).action(context(input, response));
                assertThat(((GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow()).text())
                        .isEqualTo("command=fetch");
            }
            assertThat(response.toString(StandardCharsets.US_ASCII)).isEqualTo("0000");

            bytes.reset();
            new GitPktLine.Data("symrefs\n".getBytes(StandardCharsets.UTF_8)).writeTo(request);
            response.reset();
            try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
                assertThatThrownBy(() -> command(storage, index).action(context(input, response)))
                        .isInstanceOf(IOException.class);
            }
            assertThat(response.size()).isZero();
        }
    }

    @Test
    void rejectsInvalidArgumentsControlsAndUnadvertisedUnborn() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            for (String argument : List.of("peel extra", "ref-prefix", "unknown", "symrefs\t")) {
                assertThatThrownBy(() -> execute(storage, index, argument)).isInstanceOf(IOException.class);
            }
            for (GitPktLine.Control control : List.of(GitPktLine.Control.DELIMITER,
                    GitPktLine.Control.RESPONSE_END)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                control.writeTo(new OutputStreamBufferedByteOutput(bytes));
                try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
                    ByteArrayOutputStream response = new ByteArrayOutputStream();
                    assertThatThrownBy(() -> command(storage, index).action(context(input, response)))
                            .isInstanceOf(IOException.class);
                    assertThat(response.size()).isZero();
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
            new GitPktLine.Data("unborn\n".getBytes(StandardCharsets.UTF_8)).writeTo(request);
            GitPktLine.Control.FLUSH.writeTo(request);
            try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
                assertThatThrownBy(() -> new RefsCommand(storage, index, new GitCapabilities())
                        .action(context(input, new ByteArrayOutputStream()))).isInstanceOf(IOException.class);
            }
            try (BufferedByteInputV2 input = input("0000".getBytes(StandardCharsets.US_ASCII))) {
                GitProtocolContext legacy = new GitProtocolContext(input,
                        new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                        GitProtocolVersion.V1, GitTransport.SSH);
                assertThatThrownBy(() -> command(storage, index).action(legacy)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void omitsPeeledAttributeForMissingTagTarget() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            ObjectId missing = new ObjectId("f".repeat(40));
            ObjectId tag = publish(storage, index, GitObjectType.TAG, tag(missing, "blob", "broken"));
            addRef(index, "refs/tags/broken", tag);
            assertThat(execute(storage, index)).containsExactly(tag + " refs/tags/broken");
            assertThat(execute(storage, index, "peel")).containsExactly(tag + " refs/tags/broken");
        }
    }

    @Test
    void rejectsExcessivePrefixesAndMalformedUtf8BeforeResponding() throws Exception {
        GitStorageApi storage = new LocalGitStorage(repository);
        try (GitIndexAccess index = new LocalGitIndex(repository).createAccess()) {
            String[] prefixes = new String[257];
            Arrays.fill(prefixes, "ref-prefix refs/heads/");
            assertThatThrownBy(() -> execute(storage, index, prefixes)).isInstanceOf(IOException.class)
                    .hasMessageContaining("Too many");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
            new GitPktLine.Data(new byte[]{(byte) 0xc3, 0x28}).writeTo(request);
            GitPktLine.Control.FLUSH.writeTo(request);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
                assertThatThrownBy(() -> command(storage, index).action(context(input, response)))
                        .isInstanceOf(IOException.class);
            }
            assertThat(response.size()).isZero();
        }
    }

    private RefsCommand command(GitStorageApi storage, GitIndexAccess index) {
        GitCapabilities advertised = new GitCapabilities();
        advertised.add(GitCapabilityValue.value(GitCapability.LS_REFS, "unborn"));
        return new RefsCommand(storage, index, advertised);
    }

    private List<String> execute(GitStorageApi storage, GitIndexAccess index, String... arguments) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
        for (String argument : arguments) {
            new GitPktLine.Data((argument + "\n").getBytes(StandardCharsets.UTF_8)).writeTo(request);
        }
        GitPktLine.Control.FLUSH.writeTo(request);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
            command(storage, index).action(context(input, response));
        }
        List<String> lines = new ArrayList<>();
        try (BufferedByteInputV2 input = input(response.toByteArray())) {
            GitPktLine packet;
            while ((packet = GitPktLine.readNextFrom(input).orElseThrow()) instanceof GitPktLine.Data data) {
                lines.add(data.text());
            }
            assertThat(packet).isEqualTo(GitPktLine.Control.FLUSH);
            assertThat(GitPktLine.readNextFrom(input)).isEmpty();
        }
        return lines;
    }

    private static GitProtocolContext context(BufferedByteInputV2 input, ByteArrayOutputStream response) {
        return new GitProtocolContext(input, new OutputStreamBufferedByteOutput(response),
                GitProtocolVersion.V2, GitTransport.SSH);
    }

    private static void addRef(GitIndexAccess index, String name, ObjectId id) {
        assertThat(index.updateRefs(List.of(new RefUpdate(new RefId(name), Optional.empty(),
                Optional.of(id))), true)).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.APPLIED);
    }

    private ObjectId publish(GitStorageApi storage, GitIndexAccess index, GitObjectType type, String text) throws Exception {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = GitHashAlgorithm.SHA1.newDigest();
        digest.update((type.name().toLowerCase(Locale.ROOT) + " " + content.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        ObjectId id = new ObjectId(digest.digest(content));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
             BufferedByteInputV2 input = new BufferedByteInputV2(
                     new ByteArrayInputStream(content))) {
            writer.writeObject(type, content.length, input);
            writer.finish();
        }
        try (BufferedByteInputV2 input = input(bytes.toByteArray());
             PackIngestor ingestor = new PackIngestor(input, storage, index)) {
            index.publishIndex(ingestor.ingest());
        }
        return id;
    }

    private static String tag(ObjectId target, String type, String name) {
        return "object " + target + "\ntype " + type + "\ntag " + name + "\n\nmessage\n";
    }

    private BufferedByteInputV2 input(byte[] bytes) {
        source = ByteBuffer.wrap(bytes);
        return new BufferedByteInputV2(this);
    }

    @Override
    public ByteBuffer read() {
        return source.hasRemaining() ? source : null;
    }

    @Override
    public void release() {}

    @Override
    public void close() {}
}
