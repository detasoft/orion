package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class PushCommandTest {
    @TempDir
    Path directory;

    @Test
    void publishesAThinPackWithItsExternalBaseAndLeavesTheInputOpen() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId base = store(storage, GitObjectType.BLOB, new byte[]{1, 2, 3});
        byte[] source = pack(delta(base, new byte[]{3, 4, (byte) 0x90, 3, 1, 4}));
        try (BufferedByteInputV2 input = new BufferedByteInputV2(
                new ByteArrayInputStream(join(source, new byte[]{42})))) {
            new PushCommand(storage).action(context(input));
            assertThat(input.readUnsignedByte()).isEqualTo(42);
        }
        GitStorageApi reopened = new GitStorageApi(directory);
        ObjectId result = objectId(GitObjectType.BLOB, new byte[]{1, 2, 3, 4});
        assertThat(reopened.readObject(result, new ResolvedGitObjectRead<>(reopened,
                (type, size, unused, input) -> input.readBytes((int) size))))
                .hasValueSatisfying(content -> assertThat(content).containsExactly(1, 2, 3, 4));
        PackId id = reopened.findPacksByObjectIds(List.of(result)).get(result).getFirst();
        String hex = id.toHex();
        Path shard = directory.resolve("packs").resolve(hex.substring(0, 2));
        try (IndexedPack published = IndexedPack.open(shard.resolve(hex.substring(2) + ".pack"),
                shard.resolve(hex.substring(2) + ".mv"))) {
            assertThat(published.objectCount()).isEqualTo(2);
            assertThat(published.find(base).orElseThrow().type()).isEqualTo(GitObjectType.BLOB);
        }
        assertThat(directory.resolve("incoming")).isEmptyDirectory();
    }

    @Test
    void removesStagingAfterMissingBasesMalformedDeltasAndCorruptInput() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId absent = objectId(GitObjectType.BLOB, new byte[]{1});
        byte[] corrupt = pack(blob(new byte[]{1}));
        corrupt[corrupt.length - 1] ^= 1;
        byte[][] inputs = {pack(delta(absent, new byte[]{1, 1, 1, 2})),
                pack(blob(new byte[]{1}), delta(absent, new byte[]{1, 1, 0})), corrupt};
        for (byte[] source : inputs) {
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(source))) {
                assertThatThrownBy(() -> new PushCommand(storage).action(context(input)))
                        .isInstanceOf(IOException.class);
            }
            assertThat(directory.resolve("incoming")).isEmptyDirectory();
            assertThat(directory.resolve("packs")).isEmptyDirectory();
        }
    }

    private static GitProtocolContext context(BufferedByteInputV2 input) {
        return new GitProtocolContext(input, new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                GitProtocolVersion.V0, GitTransport.SSH);
    }
}
