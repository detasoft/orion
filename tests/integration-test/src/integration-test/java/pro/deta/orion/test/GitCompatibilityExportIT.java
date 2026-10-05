package pro.deta.orion.test;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TransportHttp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class GitCompatibilityExportIT {
    private static final String REPOSITORY = "git-compat-fixture";

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({
            "is-root-f53f4653.zip,f53f4653db8e703d9d30fb88335d210e97d6f991,16,4",
            "slugify-3b17b2e8.zip,3b17b2e84b97624a683aafaa38184bf2746fab22,78,24"
    })
    void importsGitHubHistoryIntoLocalOrionThenExportsACloneableBareRepository(
            String fixture, String expectedHead, int commits, int tags) throws Exception {
        Path source = directory.resolve("source");
        unzipFixture(source, fixture);
        Path orionRoot = directory.resolve("orion");
        BootstrapConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (Git git = Git.open(source.toFile());
             RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            ObjectId head = git.getRepository().resolve("HEAD");
            assertThat(head.name()).isEqualTo(expectedHead);
            String rootToken = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.component().configurationEditor(), 600);
            TransportConfigCallback authorization = transport -> {
                if (transport instanceof TransportHttp http) {
                    http.setAdditionalHeaders(Map.of("Authorization", TestBearerTokens.bearer(rootToken)));
                }
            };
            String remote = orion.httpUrl("/r/" + REPOSITORY + ".git").toString();
            Iterable<PushResult> results = git.push().setRemote(remote)
                    .setTransportConfigCallback(authorization)
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"),
                            new RefSpec("refs/tags/*:refs/tags/*"))
                    .call();
            List<RemoteRefUpdate.Status> statuses = new ArrayList<>();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    statuses.add(update.getStatus());
                }
            }
            assertThat(statuses).hasSize(tags + 1).containsOnly(RemoteRefUpdate.Status.OK);
        }

        Path bare = directory.resolve("exported.git");
        Path exporter = Path.of(System.getProperty("git.compat.tool.jar"));
        assertThat(Files.isRegularFile(exporter)).isTrue();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        run(directory, java, "-jar", exporter.toString(), "export", "--store",
                orionRoot.resolve("repos").toString(), "--repository", REPOSITORY,
                "--output", bare.toString());

        Path clone = directory.resolve("clone");
        run(directory, "git", "clone", "--no-local", bare.toString(), clone.toString());
        assertThat(run(directory, "git", "-C", clone.toString(), "branch", "--show-current").trim())
                .isEqualTo("main");
        assertThat(run(directory, "git", "-C", clone.toString(), "rev-list", "--count", "HEAD").trim())
                .isEqualTo(Integer.toString(commits));
        List<String> sourceTags = run(directory, "git", "-C", source.toString(),
                "tag", "--list").lines().toList();
        assertThat(sourceTags).hasSize(tags);
        assertThat(run(directory, "git", "-C", clone.toString(), "tag", "--list").lines().toList())
                .containsExactlyElementsOf(sourceTags);
        List<String> files = run(directory, "git", "-C", source.toString(),
                "ls-tree", "-r", "--name-only", "HEAD").lines().toList();
        assertThat(files).hasSizeGreaterThan(1);
        assertThat(run(directory, "git", "-C", clone.toString(),
                "ls-tree", "-r", "--name-only", "HEAD").lines().toList()).containsExactlyElementsOf(files);
        for (String file : files) {
            assertThat(Files.readAllBytes(clone.resolve(file)))
                    .isEqualTo(Files.readAllBytes(source.resolve(file)));
        }
    }

    private static void unzipFixture(Path destination, String fixture) throws Exception {
        InputStream input = Objects.requireNonNull(GitCompatibilityExportIT.class
                .getResourceAsStream("/git-compat/" + fixture));
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path file = destination.resolve(entry.getName()).normalize();
                if (!file.startsWith(destination)) {
                    throw new IllegalArgumentException("Fixture contains a path outside its directory");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.copy(zip, file);
                }
            }
        }
    }

    private static String run(Path workingDirectory, String... command) throws Exception {
        Path outputFile = Files.createTempFile(workingDirectory, "git-compat-command-", ".log");
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        if (!process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Command timed out: " + String.join(" ", command));
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as("%s: %s", String.join(" ", command), output).isZero();
        return output;
    }
}
