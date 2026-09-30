package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.push.PushRequest;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.GitRepositoryContext.publishRefs;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class PushCommandTest {
    private static final String ZERO = "0".repeat(40);
    private static final RefId REF = new RefId("refs/tags/result");
    @TempDir
    Path directory;
    private GitIndexApi indexApi;

    @BeforeEach
    void openIndex() throws IOException {
        indexApi = new LocalGitIndex(directory);
    }

    @Test
    void memoryPushKeepsPublishedPackReadableAfterCommandReturns() throws Exception {
        indexApi = new InMemoryIndex();
        {
            try (GitStorageApi storage = new InMemoryStorage()) {
                indexApi.withAccess(index -> {
                    ObjectId base = store(storage, index, GitObjectType.BLOB, new byte[]{1});
                    ObjectId result = objectId(GitObjectType.BLOB, new byte[]{2});
                    byte[] response = execute(storage, request(pack(delta(base, new byte[]{1, 1, 1, 2})),
                            ZERO + " " + result + " " + REF + "\0report-status"));
                    assertThat(response).isEqualTo(report("unpack ok\n", "ok " + REF + "\n"));
                    assertThat(index.snapshotRefs().refs()).containsEntry(REF, result);
                    assertThat(GitObjectRead.read(storage, index, result, new ResolvedGitObjectRead<>(storage, index,
                            (type, size, unused, input) -> input.readBytes((int) size))))
                            .hasValueSatisfying(content -> assertThat(content).containsExactly(2));
                    return null;
                });
            }
        }
    }

    @Test
    void publishesAThinPackWithItsExternalBaseAndLeavesTheInputOpen() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId base = store(storage, index, GitObjectType.BLOB, new byte[]{1, 2, 3});
            byte[] source = pack(delta(base, new byte[]{3, 4, (byte) 0x90, 3, 1, 4}));
            ObjectId result = objectId(GitObjectType.BLOB, new byte[]{1, 2, 3, 4});
            byte[] request = request(source, ZERO + " " + result + " " + REF + "\0report-status\n");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (BufferedByteInputV2 input = new BufferedByteInputV2(
                    new ByteArrayInputStream(join(request, new byte[]{42})))) {
                runPush(storage, advertised(), context(input, output));
                assertThat(input.readUnsignedByte()).isEqualTo(42);
            }
            assertThat(output.toByteArray()).isEqualTo(report("unpack ok\n", "ok " + REF + "\n"));
            GitStorageApi reopened = new LocalGitStorage(directory);
            {
                GitIndexAccess reopenedIndex = new LocalGitIndex(directory).createAccess();
                try {
                    assertThat(reopenedIndex.snapshotRefs().refs()).containsEntry(REF, result);
                    assertThat(GitObjectRead.read(reopened, reopenedIndex, result, new ResolvedGitObjectRead<>(reopened, reopenedIndex,
                            (type, size, unused, input) -> input.readBytes((int) size))))
                            .hasValueSatisfying(content -> assertThat(content).containsExactly(1, 2, 3, 4));
                    var location = reopenedIndex.locations(result).getFirst();
                    assertThat(reopenedIndex.objects(location.packId())).hasSize(2);
                    assertThat(reopenedIndex.findObject(location.packId(), base).orElseThrow().type())
                            .isEqualTo(GitObjectType.BLOB);
                } finally {
                    reopenedIndex.discard();
                }
            }
            return null;
        });
    }

    @Test
    void keepsFailuresPrivateAfterMissingBasesMalformedDeltasAndCorruptInput() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId absent = objectId(GitObjectType.BLOB, new byte[]{1});
            byte[] corrupt = pack(blob(new byte[]{1}));
            corrupt[corrupt.length - 1] ^= 1;
            byte[][] inputs = {pack(delta(absent, new byte[]{1, 1, 1, 2})),
                    pack(blob(new byte[]{1}), delta(absent, new byte[]{1, 1, 0})), corrupt};
            for (byte[] source : inputs) {
                byte[] request = request(source, ZERO + " " + absent + " " + REF);
                try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(request))) {
                    assertThatThrownBy(() -> runPush(storage, advertised(), context(input, new ByteArrayOutputStream())))
                            .isInstanceOf(IOException.class);
                }

                assertThat(index.packs()).isEmpty();
                assertThat(index.snapshotRefs().refs()).isEmpty();
            }
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"report-status", "report-status-v2", "report-status side-band-64k",
            "report-status-v2 side-band-64k", " report-status", " report-status-v2 side-band-64k"})
    void createsUpdatesAndDeletesRefsWithNegotiatedStatus(String capabilities) throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId first = objectId(GitObjectType.BLOB, new byte[]{1});
            ObjectId second = objectId(GitObjectType.BLOB, new byte[]{2});
            byte[] expected = report("unpack ok\n", "ok " + REF + "\n");
            byte[] created = execute(storage,
                    request(pack(blob(new byte[]{1})), ZERO + " " + first + " " + REF + "\0" + capabilities));
            assertThat(status(created, capabilities)).isEqualTo(expected);
            assertThat(index.snapshotRefs().refs()).containsEntry(REF, first);
            byte[] updated = execute(storage,
                    request(pack(blob(new byte[]{2})), first + " " + second + " " + REF + "\0" + capabilities));
            assertThat(status(updated, capabilities)).isEqualTo(expected);
            assertThat(index.snapshotRefs().refs()).containsEntry(REF, second);
            byte[] deleted = execute(storage, request(new byte[0], second + " " + ZERO + " " + REF
                    + "\0" + capabilities));
            assertThat(status(deleted, capabilities)).isEqualTo(expected);
            assertThat(new LocalGitIndex(directory).createAccess().snapshotRefs().refs()).isEmpty();
            return null;
        });
    }

    @Test
    void consumesEmptyPackWhenCreatingARefToAnExistingObject() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId id = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            byte[] request = request(pack(), ZERO + " " + id + " " + REF + "\0report-status");
            try (BufferedByteInputV2 input = new BufferedByteInputV2(
                    new ByteArrayInputStream(join(request, new byte[]{42})))) {
                runPush(storage, advertised(), context(input, new ByteArrayOutputStream()));
                assertThat(input.readUnsignedByte()).isEqualTo(42);
            }
            assertThat(index.snapshotRefs().refs()).containsEntry(REF, id);
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExpectedOldAndAtomicSemantics(boolean atomic) throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId first = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            ObjectId stale = objectId(GitObjectType.BLOB, new byte[]{2});
            ObjectId next = objectId(GitObjectType.BLOB, new byte[]{3});
            publishRefs(
                    storage, indexApi,
                    List.of(new RefUpdate(REF, Optional.empty(), Optional.of(first))), false);
            RefId other = new RefId("refs/tags/other");
            byte[] response = execute(storage, request(pack(blob(new byte[]{3})),
                    stale + " " + next + " " + REF + "\0report-status" + (atomic ? " atomic" : ""),
                    ZERO + " " + next + " " + other));
            assertThat(response).isEqualTo(report("unpack ok\n", "ng " + REF + " stale info\n",
                    atomic ? "ng " + other + " atomic push failure\n" : "ok " + other + "\n"));
            assertThat(index.snapshotRefs().refs()).containsEntry(REF, first);
            if (atomic) {
                assertThat(index.snapshotRefs().refs()).doesNotContainKey(other);
            } else {
                assertThat(index.snapshotRefs().refs()).containsEntry(other, next);
            }
            assertThat(GitObjectRead.exists(storage, index, next)).isTrue();
            return null;
        });
    }

    @Test
    void reportsMissingTargetsWithoutCreatingRefs() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId missing = objectId(GitObjectType.BLOB, new byte[]{1});
            byte[] response = execute(storage, request(pack(),
                    ZERO + " " + missing + " " + REF + "\0report-status"));
            assertThat(response).isEqualTo(report("unpack ok\n", "ng " + REF + " missing necessary objects\n"));
            assertThat(index.snapshotRefs().refs()).isEmpty();
            return null;
        });
    }

    @Test
    void reportsUnpackFailureAndDoesNotApplyEvenADeletion() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId first = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            publishRefs(
                    storage, indexApi,
                    List.of(new RefUpdate(REF, Optional.empty(), Optional.of(first))), false);
            ObjectId next = objectId(GitObjectType.BLOB, new byte[]{2});
            byte[] corrupt = pack(blob(new byte[]{2}));
            corrupt[corrupt.length - 1] ^= 1;
            byte[] response = execute(storage, request(corrupt,
                    first + " " + ZERO + " " + REF + "\0report-status side-band-64k",
                    ZERO + " " + next + " refs/tags/other"));
            assertThat(status(response, "side-band-64k")).isEqualTo(report("unpack unpacker error\n",
                    "ng " + REF + " unpacker error\n", "ng refs/tags/other unpacker error\n"));
            assertThat(index.snapshotRefs().refs()).containsOnlyKeys(REF).containsEntry(REF, first);
            assertThat(GitObjectRead.exists(storage, index, next)).isFalse();
            return null;
        });
    }

    @Test
    void sendsOnlyTheNegotiatedResponseAndAcceptsCancellation() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId id = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            assertThat(execute(storage, request(pack(), ZERO + " " + id + " " + REF))).isEmpty();
            assertThat(execute(storage, request(new byte[0], id + " " + ZERO + " " + REF + "\0side-band-64k")))
                    .isEqualTo("0000".getBytes(StandardCharsets.US_ASCII));
            assertThat(execute(storage, request(new byte[0]))).isEmpty();
            assertThat(index.snapshotRefs().refs()).isEmpty();
            return null;
        });
    }

    @Test
    void rejectsMalformedRequestsWithoutChangingExistingRefs() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            ObjectId existing = store(storage, index, GitObjectType.BLOB, new byte[]{2});
            publishRefs(
                    storage, indexApi,
                    List.of(new RefUpdate(REF, Optional.empty(), Optional.of(existing))), false);
            String id = objectId(GitObjectType.BLOB, new byte[]{1}).toHex();
            String create = ZERO + " " + id + " " + REF;
            List<byte[]> requests = List.of(
                    request(new byte[0], create + "\0unknown"),
                    request(new byte[0], create + "\0atomic=true"),
                    request(new byte[0], create + "\0report-status report-status"),
                    request(new byte[0], create + "\0report-status  atomic"),
                    request(new byte[0], create + "\0push-options"),
                    request(new byte[0], create + "\0object-format=sha256"),
                    request(new byte[0], create + "\0object-format"),
                    request(new byte[0], create + "\0report-status\0atomic"),
                    request(new byte[0], create + "\n\0report-status"),
                    request(new byte[0], create, ZERO + " " + id + " refs/tags/other\0report-status"),
                    request(new byte[0], create, create),
                    request(new byte[0], existing + " " + ZERO + " " + REF, "invalid update"),
                    request(new byte[0], "shallow " + "g".repeat(40), create),
                    request(new byte[0], create, "shallow " + id),
                    request(new byte[0], ZERO + " " + ZERO + " " + REF),
                    request(new byte[0], ZERO + " " + "g".repeat(40) + " " + REF),
                    request(new byte[0], ZERO + " " + id + " refs/heads/a..b"),
                    request(new byte[0], ZERO + " " + id + " HEAD"),
                    "0001".getBytes(StandardCharsets.US_ASCII),
                    "0002".getBytes(StandardCharsets.US_ASCII),
                    "000".getBytes(StandardCharsets.US_ASCII));
            for (byte[] request : requests) {
                try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(request))) {
                    assertThatThrownBy(() -> runPush(storage, advertised(), context(input, new ByteArrayOutputStream())))
                            .isInstanceOf(IOException.class);
                }
                assertThat(index.snapshotRefs().refs()).containsOnlyKeys(REF).containsEntry(REF, existing);
                assertThat(GitObjectRead.exists(storage, index, new ObjectId(id))).isFalse();
            }

            assertThat(GitObjectRead.exists(storage, index, existing)).isTrue();
            return null;
        });
    }

    @Test
    void rejectsUnadvertisedCapabilitiesAndDeletionAndV2() throws Exception {
        GitStorageApi storage = new LocalGitStorage(directory);
        new LocalGitIndex(directory).withAccess(index -> {
            String id = objectId(GitObjectType.BLOB, new byte[]{1}).toHex();
            for (String line : List.of(ZERO + " " + id + " " + REF + "\0atomic",
                    id + " " + ZERO + " " + REF)) {
                try (BufferedByteInputV2 input = new BufferedByteInputV2(
                        new ByteArrayInputStream(request(new byte[0], line)))) {
                    assertThatThrownBy(() -> runPush(storage, new GitCapabilities(), context(input, new ByteArrayOutputStream())))
                            .isInstanceOf(IOException.class);
                }
            }
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(new byte[0]))) {
                GitProtocolContext context = new GitProtocolContext(input,
                        new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                        GitProtocolVersion.V2, GitTransport.SSH);
                assertThatThrownBy(() -> runPush(storage, advertised(), context))
                        .isInstanceOf(IOException.class).hasMessageContaining("legacy push protocol");
            }
            return null;
        });
    }

    @Test
    void parsesUnicodeRefsAndValuedCapabilitiesAndLeavesThePackUnread() throws Exception {
        String hex = "abcdef1234".repeat(4);
        String name = "refs/heads/ветка";
        byte[] request = request(new byte[]{42}, ZERO + " " + hex.toUpperCase(Locale.ROOT)
                + " " + name + "\0report-status object-format=sha1 agent=git/test session-id=123 quiet ofs-delta\n");
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(request))) {
            PushRequest parsed = PushRequest.parse(context(input, new ByteArrayOutputStream()).reader(), advertised());
            assertThat(parsed.updates()).containsExactly(new RefUpdate(new RefId(name),
                    Optional.empty(), Optional.of(new ObjectId(hex))));
            assertThat(parsed.requiresPack()).isTrue();
            assertThat(parsed.capabilities().value(GitCapability.OBJECT_FORMAT)).contains("sha1");
            assertThat(parsed.capabilities().value(GitCapability.AGENT)).contains("git/test");
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
    }

    private void runPush(GitStorageApi storage, GitCapabilities capabilities, GitProtocolContext protocol)
            throws IOException {
        try (GitRepositoryContext repository = new GitRepositoryContext(storage, indexApi)) {
            new PushCommand(repository, capabilities).action(protocol);
        }
    }

    private byte[] execute(GitStorageApi storage, byte[] request) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(request))) {
            runPush(storage, advertised(), context(input, output));
        }
        return output.toByteArray();
    }

    private static byte[] status(byte[] response, String capabilities) throws IOException {
        if (!capabilities.contains("side-band-64k")) {
            return response;
        }
        ByteArrayOutputStream status = new ByteArrayOutputStream();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(response))) {
            for (;;) {
                GitPktLine packet = GitPktLine.readNextFrom(input).orElseThrow();
                if (packet == GitPktLine.Control.FLUSH) {
                    assertThat(GitPktLine.readNextFrom(input)).isEmpty();
                    return status.toByteArray();
                }
                assertThat(packet).isInstanceOf(GitPktLine.Data.class);
                byte[] payload = ((GitPktLine.Data) packet).content();
                assertThat(payload[0]).isEqualTo((byte) 1);
                status.write(payload, 1, payload.length - 1);
            }
        }
    }

    private static byte[] request(byte[] pack, String... commands) {
        return join(report(commands), pack);
    }

    private static byte[] report(String... lines) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String line : lines) {
            byte[] payload = line.getBytes(StandardCharsets.UTF_8);
            output.writeBytes(String.format("%04x", payload.length + 4).getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(payload);
        }
        output.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static GitCapabilities advertised() {
        GitCapabilities capabilities = new GitCapabilities();
        for (GitCapability capability : List.of(GitCapability.REPORT_STATUS, GitCapability.REPORT_STATUS_V2,
                GitCapability.SIDE_BAND_64K, GitCapability.ATOMIC, GitCapability.OFS_DELTA,
                GitCapability.DELETE_REFS, GitCapability.QUIET, GitCapability.AGENT,
                GitCapability.SESSION_ID, GitCapability.OBJECT_FORMAT, GitCapability.PUSH_OPTIONS)) {
            capabilities.add(GitCapabilityValue.value(capability));
        }
        return capabilities;
    }

    private static GitProtocolContext context(BufferedByteInputV2 input, ByteArrayOutputStream output) {
        return new GitProtocolContext(input, new OutputStreamBufferedByteOutput(output),
                GitProtocolVersion.V0, GitTransport.SSH);
    }
}
