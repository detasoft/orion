package pro.deta.orion.agentd.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePolicyParserTest {
    private final SourcePolicyParser parser = new SourcePolicyParser();

    @Test
    void parsesVersionPresetsCommentsEscapesAndLastRulePrecedence() {
        SourcePolicy policy = parser.parse("""
                # policy header
                landlock 1

                ro "/"
                rox "/bin" # executable files
                none "/home/user/.ssh"
                [rw, read-dir, make-reg] "/workspace"
                rw "/escaped\\\\path\\\"name"
                none "/bin"
                """);

        assertThat(policy.rules()).containsExactly(
                new SourcePolicy.Rule(Path.of("/"), LandlockRight.READ_FILE.mask(), 4),
                new SourcePolicy.Rule(Path.of("/home/user/.ssh"), 0, 6),
                new SourcePolicy.Rule(
                        Path.of("/workspace"),
                        LandlockRight.READ_FILE.mask()
                                | LandlockRight.WRITE_FILE.mask()
                                | LandlockRight.TRUNCATE.mask()
                                | LandlockRight.READ_DIR.mask()
                                | LandlockRight.MAKE_REG.mask(),
                        7),
                new SourcePolicy.Rule(
                        Path.of("/escaped\\path\"name"),
                        LandlockRight.READ_FILE.mask()
                                | LandlockRight.WRITE_FILE.mask()
                                | LandlockRight.TRUNCATE.mask(),
                        8),
                new SourcePolicy.Rule(Path.of("/bin"), 0, 9));
    }

    @Test
    void exposesAllAbiNineRightsAndPresets() {
        SourcePolicy policy = parser.parse("""
                landlock 1
                rox "/ro"
                rwx "/rw"
                [execute,write-file,read-file,read-dir,remove-dir,remove-file,make-char,make-dir,make-reg,\
                make-sock,make-fifo,make-block,make-sym,refer,truncate,ioctl-dev,resolve-unix] "/all"
                """);

        assertThat(policy.rules().get(0).rights())
                .isEqualTo(LandlockRight.READ_FILE.mask() | LandlockRight.EXECUTE.mask());
        assertThat(policy.rules().get(1).rights()).isEqualTo(
                LandlockRight.READ_FILE.mask() | LandlockRight.WRITE_FILE.mask()
                        | LandlockRight.TRUNCATE.mask() | LandlockRight.EXECUTE.mask());
        assertThat(policy.rules().get(2).rights()).isEqualTo(LandlockRight.HANDLED_MASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ro \"/\"",
            "landlock 2\nro \"/\"",
            "landlock 1\nunknown \"/\"",
            "landlock 1\n[none, ro] \"/\"",
            "landlock 1\n[ro, ro] \"/\"",
            "landlock 1\n[] \"/\"",
            "landlock 1\nro \"relative\"",
            "landlock 1\nro \"/a/../b\"",
            "landlock 1\nro \"/a/./b\"",
            "landlock 1\nro \"/bad\\npath\"",
            "landlock 1\nro \"/\" trailing"
    })
    void rejectsInvalidSource(String source) {
        assertThatThrownBy(() -> parser.parse(source))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("line");
    }

    @Test
    void rejectsOversizedSourceAndTooManyRules() {
        String oversized = "landlock 1\n#" + "x".repeat(SourcePolicyParser.MAX_SOURCE_BYTES);
        assertThatThrownBy(() -> parser.parse(oversized))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("too large");
        StringBuilder source = new StringBuilder("landlock 1\n");
        for (int index = 0; index <= SourcePolicyParser.MAX_RULES; index++) {
            source.append("none \"").append('/').append(index).append("\"\n");
        }
        assertThatThrownBy(() -> parser.parse(source.toString()))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("too many rules");
    }
}
