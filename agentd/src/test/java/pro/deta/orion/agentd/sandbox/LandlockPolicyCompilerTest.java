package pro.deta.orion.agentd.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LandlockPolicyCompilerTest {
    @TempDir
    Path temporaryDirectory;
    private final SourcePolicyParser parser = new SourcePolicyParser();
    private final LandlockPolicyCompiler compiler = new LandlockPolicyCompiler();

    @Test
    void splitsAnAllowedRegionAroundDeniedCurrentSiblings() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.toRealPath().resolve("root"));
        Path bin = Files.createDirectory(root.resolve("bin"));
        Path home = Files.createDirectory(root.resolve("home"));
        Path usr = Files.createDirectory(root.resolve("usr"));

        CompiledPolicy policy = compiler.compile(parser.parse("""
                landlock 1
                ro "%s"
                none "%s"
                """.formatted(root, home)));

        assertThat(policy.rules()).containsExactly(
                new CompiledPolicy.Rule(bin, LandlockRight.READ_FILE.mask()),
                new CompiledPolicy.Rule(usr, LandlockRight.READ_FILE.mask()));
    }

    @Test
    void splitsAtEveryBoundaryAndDoesNotIncludeLaterSiblings() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.toRealPath().resolve("root"));
        Path etc = Files.createDirectory(root.resolve("etc"));
        Path home = Files.createDirectory(root.resolve("home"));
        Path otherUser = Files.createDirectory(home.resolve("other"));
        Path user = Files.createDirectory(home.resolve("user"));
        Path documents = Files.createDirectory(user.resolve("documents"));
        Path ssh = Files.createDirectory(user.resolve(".ssh"));
        CompiledPolicy policy = compiler.compile(parser.parse("""
                landlock 1
                ro "%s"
                none "%s"
                """.formatted(root, ssh)));
        Path later = Files.createDirectory(root.resolve("later"));

        assertThat(policy.rules()).containsExactly(
                new CompiledPolicy.Rule(etc, LandlockRight.READ_FILE.mask()),
                new CompiledPolicy.Rule(otherUser, LandlockRight.READ_FILE.mask()),
                new CompiledPolicy.Rule(documents, LandlockRight.READ_FILE.mask()));
        assertThat(policy.rules()).extracting(CompiledPolicy.Rule::path).doesNotContain(later);
    }

    @Test
    void combinesAncestorAndDeeperAdditionalRights() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.toRealPath().resolve("root"));
        Path child = Files.createDirectory(root.resolve("child"));

        CompiledPolicy policy = compiler.compile(parser.parse("""
                landlock 1
                ro "%s"
                rox "%s"
                """.formatted(root, child)));

        assertThat(policy.rules()).containsExactly(
                new CompiledPolicy.Rule(root, LandlockRight.READ_FILE.mask()),
                new CompiledPolicy.Rule(child, LandlockRight.EXECUTE.mask()));
    }

    @Test
    void missingDenyIsValidButMissingAllowAndSymlinkComponentFail() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.toRealPath().resolve("root"));
        Path existing = Files.createDirectory(root.resolve("existing"));
        CompiledPolicy compiled = compiler.compile(parser.parse("""
                landlock 1
                ro "%s"
                none "%s"
                """.formatted(root, root.resolve("missing/child"))));

        assertThat(compiled.rules()).containsExactly(
                new CompiledPolicy.Rule(existing, LandlockRight.READ_FILE.mask()));
        assertThatThrownBy(() -> compiler.compile(parser.parse("""
                landlock 1
                ro "%s"
                """.formatted(root.resolve("absent")))))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("missing positive path");

        Path link = root.resolve("link");
        Files.createSymbolicLink(link, existing);
        assertThatThrownBy(() -> compiler.compile(parser.parse("""
                landlock 1
                ro "%s"
                """.formatted(link))))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("symbolic-link component");
    }

    @Test
    void rejectsDirectoryOperationThatCannotExcludeDescendant() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.toRealPath().resolve("root"));
        Path denied = Files.createDirectory(root.resolve("denied"));

        assertThatThrownBy(() -> compiler.compile(parser.parse("""
                landlock 1
                [read-dir, make-reg] "%s"
                none "%s"
                """.formatted(root, denied))))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("cannot represent")
                .hasMessageContaining(denied.toString());
    }

    @Test
    void skipsSymlinkEntriesAndSortsPathsByRawUtfEight() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.toRealPath().resolve("root"));
        Path zed = Files.createDirectory(root.resolve("z"));
        Path accented = Files.createDirectory(root.resolve("é"));
        Path denied = Files.createDirectory(root.resolve("denied"));
        Files.createSymbolicLink(root.resolve("link"), zed);

        CompiledPolicy compiled = compiler.compile(parser.parse("""
                landlock 1
                ro "%s"
                none "%s"
                """.formatted(root, denied)));

        assertThat(compiled.rules()).containsExactly(
                new CompiledPolicy.Rule(zed, LandlockRight.READ_FILE.mask()),
                new CompiledPolicy.Rule(accented, LandlockRight.READ_FILE.mask()));
    }
}
