package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FetchPackTest {
    @TempDir
    Path directory;

    @Test
    void writesPreparedEntriesInOrderWithoutReopeningTheirIndexes() throws Exception {
        try (GitStorageApi storage = new GitStorageApi(directory)) {
            byte[] first = {1, 2, 3};
            byte[] second = {4, 5};
            ObjectId firstId = PackTestData.store(storage, GitObjectType.BLOB, first);
            ObjectId secondId = PackTestData.store(storage, GitObjectType.BLOB, second);
            FetchPlan plan = new FetchPlan(new LinkedHashSet<>(List.of(secondId, firstId)), Map.of(),
                    Set.of(), Set.of(), OptionalInt.empty(), OptionalLong.empty(), Set.of(), Optional.empty(),
                    new GitCapabilities(), Set.of());
            FetchPack pack = FetchPack.prepare(storage, plan);
            assertThat(pack.objectCount()).isEqualTo(2);
            for (PackId id : storage.packIds()) {
                String hex = id.toHex();
                Path index = directory.resolve("packs").resolve(hex.substring(0, 2))
                        .resolve(hex.substring(2) + ".mv");
                Files.move(index, index.resolveSibling(index.getFileName() + ".saved"));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(output), pack.objectCount())) {
                pack.writeTo(writer);
                writer.finish();
            }
            assertThat(output.toByteArray()).isEqualTo(
                    PackTestData.pack(PackTestData.blob(second), PackTestData.blob(first)));
        }
    }
}
