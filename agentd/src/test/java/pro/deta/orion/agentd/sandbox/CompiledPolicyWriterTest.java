package pro.deta.orion.agentd.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledPolicyWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void matchesTheSharedCanonicalFixtureAndWritesOwnerOnlyFile() throws IOException {
        CompiledPolicy policy = new CompiledPolicy(LandlockRight.HANDLED_MASK, List.of(
                new CompiledPolicy.Rule(Path.of("/workspace"), 20_926),
                new CompiledPolicy.Rule(Path.of("/usr"), 5),
                new CompiledPolicy.Rule(Path.of("/bin"), 5)));
        String fixture = Files.readString(Path.of(
                "../session-host/protocol/fixtures/sandbox-policy-v1.hex")).trim();
        CompiledPolicyWriter writer = new CompiledPolicyWriter();

        assertThat(HexFormat.of().formatHex(writer.encode(policy))).isEqualTo(fixture);
        Path output = writer.write(temporaryDirectory, policy);

        assertThat(output).isEqualTo(temporaryDirectory.resolve("sandbox-policy.cbor"));
        assertThat(Files.readAllBytes(output)).isEqualTo(HexFormat.of().parseHex(fixture));
        if (Files.getFileStore(output).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(output)).isEqualTo(Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }
    }
}
