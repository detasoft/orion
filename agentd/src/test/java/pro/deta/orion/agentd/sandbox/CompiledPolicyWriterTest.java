package pro.deta.orion.agentd.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void rejectsPoliciesBeyondSessionHostLimits() {
        List<CompiledPolicy.Rule> tooManyRules = new ArrayList<>();
        for (int index = 0; index <= 32_768; index++) {
            tooManyRules.add(new CompiledPolicy.Rule(
                    Path.of("/rule-" + index), LandlockRight.READ_FILE.mask()));
        }
        CompiledPolicy tooMany = new CompiledPolicy(LandlockRight.HANDLED_MASK, tooManyRules);

        String suffix = "x".repeat(4_080);
        List<CompiledPolicy.Rule> oversizedRules = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            oversizedRules.add(new CompiledPolicy.Rule(
                    Path.of("/" + String.format("%04x", index) + suffix),
                    LandlockRight.READ_FILE.mask()));
        }
        CompiledPolicy oversized = new CompiledPolicy(LandlockRight.HANDLED_MASK, oversizedRules);
        CompiledPolicyWriter writer = new CompiledPolicyWriter();

        assertThatThrownBy(() -> writer.encode(tooMany))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("session-host")
                .hasMessageContaining("32768");
        assertThatThrownBy(() -> writer.encode(oversized))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("session-host")
                .hasMessageContaining("1048576");
    }
}
