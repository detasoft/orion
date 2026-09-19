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
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefsCommandTest implements BufferedByteInputV2.Source {
    @TempDir
    Path repository;
    private ByteBuffer source;

    @Test
    void listsRefsWithSymbolicHeadAndFiltersByAnyRequestedPrefix() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        ObjectId commit = publish(storage, GitObjectType.COMMIT, "tree " + "0".repeat(40) + "\n\nmessage\n");
        addRef(storage, "refs/heads/main", commit);
        addRef(storage, "refs/heads/ветка", commit);
        addRef(storage, "refs/tags/lightweight", commit);
        assertThat(execute(storage, "symrefs")).containsExactly(
                commit + " HEAD symref-target:refs/heads/main",
                commit + " refs/heads/main", commit + " refs/heads/ветка", commit + " refs/tags/lightweight");
        assertThat(execute(storage, "ref-prefix HEAD", "ref-prefix refs/heads/вет"))
                .containsExactly(commit + " HEAD", commit + " refs/heads/ветка");
        assertThat(execute(storage, "ref-prefix absent")).isEmpty();
        assertThat(execute(storage, "ref-prefix ")).hasSize(4);
    }

    @Test
    void handlesUnbornAndDetachedHead() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        assertThat(execute(storage)).isEmpty();
        assertThat(execute(storage, "symrefs")).isEmpty();
        assertThat(execute(storage, "unborn")).isEmpty();
        assertThat(execute(storage, "unborn", "symrefs"))
                .containsExactly("unborn HEAD symref-target:refs/heads/main");
        assertThat(execute(storage, "unborn", "symrefs", "ref-prefix refs/")).isEmpty();
        ObjectId commit = publish(storage, GitObjectType.COMMIT, "tree " + "0".repeat(40) + "\n\nmessage\n");
        storage.updateHead(new Head.Detached(new CommitId(commit.toBytes())));
        assertThat(execute(storage, "symrefs", "unborn")).containsExactly(commit + " HEAD");
    }

    @Test
    void peelsNestedTagsIncludingRefsOutsideTheTagsNamespace() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        ObjectId blob = publish(storage, GitObjectType.BLOB, "content");
        ObjectId inner = publish(storage, GitObjectType.TAG, tag(blob, "blob", "inner"));
        ObjectId outer = publish(storage, GitObjectType.TAG, tag(inner, "tag", "outer"));
        addRef(storage, "refs/tags/nested", outer);
        addRef(storage, "refs/tags/lightweight", blob);
        addRef(storage, "refs/custom/tag", inner);
        storage.updateHead(new Head.Symbolic(new RefId("refs/custom/tag")));
        assertThat(execute(storage, "peel", "symrefs")).containsExactly(
                inner + " HEAD symref-target:refs/custom/tag peeled:" + blob,
                inner + " refs/custom/tag peeled:" + blob,
                blob + " refs/tags/lightweight", outer + " refs/tags/nested peeled:" + blob);
        assertThat(execute(storage, "ref-prefix refs/tags/nested"))
                .containsExactly(outer + " refs/tags/nested");
    }

    @Test
    void consumesOnlyThisRequestAndWritesNothingBeforeItsFlush() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
        GitPktLine.Control.FLUSH.writeTo(request);
        new GitPktLine.Data("command=fetch\n".getBytes(StandardCharsets.UTF_8)).writeTo(request);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
            command(storage).action(context(input, response));
            assertThat(((GitPktLine.Data) GitPktLine.readNextFrom(input).orElseThrow()).text())
                    .isEqualTo("command=fetch");
        }
        assertThat(response.toString(StandardCharsets.US_ASCII)).isEqualTo("0000");

        bytes.reset();
        new GitPktLine.Data("symrefs\n".getBytes(StandardCharsets.UTF_8)).writeTo(request);
        response.reset();
        try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
            assertThatThrownBy(() -> command(storage).action(context(input, response)))
                    .isInstanceOf(IOException.class);
        }
        assertThat(response.size()).isZero();
    }

    @Test
    void rejectsInvalidArgumentsControlsAndUnadvertisedUnborn() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        for (String argument : List.of("peel extra", "ref-prefix", "unknown", "symrefs\t")) {
            assertThatThrownBy(() -> execute(storage, argument)).isInstanceOf(IOException.class);
        }
        for (GitPktLine.Control control : List.of(GitPktLine.Control.DELIMITER,
                GitPktLine.Control.RESPONSE_END)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            control.writeTo(new OutputStreamBufferedByteOutput(bytes));
            try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                assertThatThrownBy(() -> command(storage).action(context(input, response)))
                        .isInstanceOf(IOException.class);
                assertThat(response.size()).isZero();
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
        new GitPktLine.Data("unborn\n".getBytes(StandardCharsets.UTF_8)).writeTo(request);
        GitPktLine.Control.FLUSH.writeTo(request);
        try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
            assertThatThrownBy(() -> new RefsCommand(storage, new GitCapabilities())
                    .action(context(input, new ByteArrayOutputStream()))).isInstanceOf(IOException.class);
        }
        try (BufferedByteInputV2 input = input("0000".getBytes(StandardCharsets.US_ASCII))) {
            GitProtocolContext legacy = new GitProtocolContext(input,
                    new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                    GitProtocolVersion.V1, GitTransport.SSH);
            assertThatThrownBy(() -> command(storage).action(legacy)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsMissingTagTargetWhenPeeling() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        ObjectId missing = new ObjectId("f".repeat(40));
        ObjectId tag = publish(storage, GitObjectType.TAG, tag(missing, "blob", "broken"));
        addRef(storage, "refs/tags/broken", tag);
        assertThat(execute(storage)).containsExactly(tag + " refs/tags/broken");
        assertThatThrownBy(() -> execute(storage, "peel")).isInstanceOf(IOException.class)
                .hasMessageContaining("Missing object");
    }

    @Test
    void rejectsExcessivePrefixesAndMalformedUtf8BeforeResponding() throws Exception {
        GitStorageApi storage = new GitStorageApi(repository);
        String[] prefixes = new String[257];
        java.util.Arrays.fill(prefixes, "ref-prefix refs/heads/");
        assertThatThrownBy(() -> execute(storage, prefixes)).isInstanceOf(IOException.class)
                .hasMessageContaining("Too many");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
        new GitPktLine.Data(new byte[]{(byte) 0xc3, 0x28}).writeTo(request);
        GitPktLine.Control.FLUSH.writeTo(request);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
            assertThatThrownBy(() -> command(storage).action(context(input, response)))
                    .isInstanceOf(IOException.class);
        }
        assertThat(response.size()).isZero();
    }

    private RefsCommand command(GitStorageApi storage) {
        GitCapabilities advertised = new GitCapabilities();
        advertised.add(GitCapabilityValue.value(GitCapability.LS_REFS, "unborn"));
        return new RefsCommand(storage, advertised);
    }

    private List<String> execute(GitStorageApi storage, String... arguments) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStreamBufferedByteOutput request = new OutputStreamBufferedByteOutput(bytes);
        for (String argument : arguments) {
            new GitPktLine.Data((argument + "\n").getBytes(StandardCharsets.UTF_8)).writeTo(request);
        }
        GitPktLine.Control.FLUSH.writeTo(request);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = input(bytes.toByteArray())) {
            command(storage).action(context(input, response));
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

    private static void addRef(GitStorageApi storage, String name, ObjectId id) {
        assertThat(storage.updateRefs(List.of(new RefUpdate(new RefId(name), Optional.empty(),
                Optional.of(id))), true)).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.APPLIED);
    }

    private ObjectId publish(GitStorageApi storage, GitObjectType type, String text) throws Exception {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = GitHashAlgorithm.SHA1.newDigest();
        digest.update((type.name().toLowerCase(java.util.Locale.ROOT) + " " + content.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        ObjectId id = new ObjectId(digest.digest(content));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
             InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(
                     new ByteArrayInputStream(content))) {
            writer.writeObject(type, content.length, input);
            writer.finish();
        }
        try (BufferedByteInputV2 input = input(bytes.toByteArray());
             PackIngestor ingestor = new PackIngestor(input, storage.newPack())) {
            storage.persist(ingestor.ingest());
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
