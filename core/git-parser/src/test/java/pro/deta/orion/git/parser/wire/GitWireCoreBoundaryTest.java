package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitWireCoreBoundaryTest {
    @Test
    void productionWireCoreDoesNotUseInputStreamAsParserBoundary() throws IOException {
        Path root = repositoryRoot();
        List<Path> offenders = new ArrayList<>();

        for (Path sourceFile : productionSources(root)) {
            String source = Files.readString(sourceFile);
            if (source.contains("java.io.InputStream") || source.contains("InputStream")) {
                offenders.add(root.relativize(sourceFile));
            }
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    void initialServiceRequestParserDoesNotMaterializeWholePayloadAsString() throws IOException {
        Path root = repositoryRoot();
        String source = Files.readString(root.resolve(
                "core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitInitialServiceRequestParser.java"));

        assertThat(source)
                .doesNotContain("new String(payload")
                .doesNotContain("parse(new String(");
    }

    @Test
    void initialServiceRequestParserUsesOnlyByteBufInputBoundary() throws IOException {
        Path root = repositoryRoot();
        String source = Files.readString(root.resolve(
                "core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitInitialServiceRequestParser.java"));

        assertThat(source)
                .doesNotContain("ByteSource")
                .doesNotContain("read(byte[]")
                .doesNotContain("Unpooled")
                .doesNotContain("java.io.");
    }

    @Test
    void initialServiceRequestParserDoesNotExposePktLineFrameWrapper() throws IOException {
        Path root = repositoryRoot();
        List<Path> offenders = new ArrayList<>();

        for (Path sourceFile : productionSources(root)) {
            if (sourceFile.getFileName().toString().equals("GitPktLineFrame.java")) {
                offenders.add(root.relativize(sourceFile));
                continue;
            }
            if (Files.readString(sourceFile).contains("GitPktLineFrame")) {
                offenders.add(root.relativize(sourceFile));
            }
        }

        assertThat(offenders).isEmpty();
    }

    private static List<Path> productionSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        collectJavaSources(root.resolve("core/git-parser/src/main/java"), sources);
        return sources;
    }

    private static void collectJavaSources(Path path, List<Path> sources) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            if (path.getFileName().toString().endsWith(".java")) {
                sources.add(path);
            }
            return;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                collectJavaSources(entry, sources);
            }
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".root")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
