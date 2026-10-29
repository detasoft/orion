package pro.deta.orion;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "orion.distribution.archive", matches = ".+")
class DistributionArchiveIT {
    @TempDir
    Path directory;

    @Test
    void archivePreservesExecutableFilesWithoutMakingDataExecutable() throws Exception {
        Path archive = archive();
        Path image = archive.resolveSibling("orion-dist");
        Set<Path> actualFiles = new HashSet<>();
        Set<String> entries = new HashSet<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                assertThat(entries.add(entry.getName())).as("unique entry %s", entry.getName()).isTrue();
                Path name = Path.of(entry.getName());
                assertThat(name.startsWith("orion")).isTrue();
                Path relative = Path.of("orion").relativize(name);
                if (entry.isFile()) {
                    actualFiles.add(relative);
                    int expectedMode = Files.isExecutable(image.resolve(relative)) ? 0755 : 0644;
                    assertThat(entry.getMode() & 0777).as("mode of %s", name).isEqualTo(expectedMode);
                } else if (entry.isDirectory()) {
                    assertThat(entry.getMode() & 0777).as("mode of %s", name).isEqualTo(0755);
                }
            }
        }
        Set<Path> expectedFiles = new HashSet<>();
        try (Stream<Path> paths = Files.walk(image)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) {
                    expectedFiles.add(image.relativize(path));
                }
            }
        }
        assertThat(actualFiles).containsExactlyInAnyOrderElementsOf(expectedFiles);
        assertThat(actualFiles).contains(
                Path.of("bin/orion"), Path.of("runtime/bin/java"), Path.of("lib/orion.jar"));
    }

    @Test
    void freshExtractionRunsWithBundledJavaWithoutChangingPermissions() throws Exception {
        run(List.of("tar", "-xzf", archive().toString(), "-C", directory.toString()));
        Path installation = directory.resolve("orion");
        assertThat(Files.isExecutable(installation.resolve("runtime/bin/java"))).isTrue();
        assertThat(Files.isExecutable(installation.resolve("lib/orion.jar"))).isFalse();
        assertThat(run(List.of(installation.resolve("bin/orion").toString(), "--help")))
                .contains("Usage: orion", "Start Orion");
    }

    private Path archive() {
        return Path.of(System.getProperty("orion.distribution.archive")).toAbsolutePath();
    }

    private String run(List<String> command) throws Exception {
        Path output = Files.createTempFile(directory, "process-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().remove("JAVA_CMD");
        builder.environment().remove("JAVA_OPTS");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("process completed: %s", command).isTrue();
            String result = Files.readString(output);
            assertThat(process.exitValue()).as("%s: %s", command, result).isZero();
            return result;
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
