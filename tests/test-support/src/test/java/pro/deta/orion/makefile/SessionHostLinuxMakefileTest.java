package pro.deta.orion.makefile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHostLinuxMakefileTest {

    @TempDir
    Path tempDir;

    @Test
    void packagesOnlySessionHostSourcesAndRunsRemoteTests() throws Exception {
        RemoteTools tools = remoteTools(0);

        MakeResult result = runMake(tools);

        assertThat(result.exitCode()).as("make output:%n%s", result.output()).isZero();
        assertThat(Files.readString(tools.archiveList())).contains(
                "Makefile",
                "session-host/Cargo.toml",
                "session-host/src/platform/unix.rs",
                "session-host/tests/unix_process_host.rs",
                "agent-protocol/protocol/fixtures/session-events-v1.hex"
        ).doesNotContain(
                ".git/",
                ".orion-cache/",
                "session-host/target/"
        );
        assertThat(Files.readString(tools.trace())).contains(
                "mkdir -p",
                "tar -xzf",
                "CARGO_TARGET_DIR=/tmp/orion-session-host-linux/cache/cargo",
                "/opt/rust/bin/cargo test --locked --manifest-path session-host/Cargo.toml"
        );
    }

    @Test
    void refreshesPackagedSourcesAfterExtractionBeforeRemoteCargoBuild() throws Exception {
        RemoteTools tools = remoteTools(0);

        MakeResult result = runMake(tools);

        assertThat(result.exitCode()).as("make output:%n%s", result.output()).isZero();
        String trace = Files.readString(tools.trace());
        int extract = trace.indexOf("tar -xzf");
        int refresh = trace.indexOf("find \"$run\" -type f -exec touch {} +");
        int build = trace.indexOf("/opt/rust/bin/cargo test --locked --manifest-path session-host/Cargo.toml");
        assertThat(extract).isGreaterThanOrEqualTo(0);
        assertThat(refresh).isGreaterThan(extract);
        assertThat(build).isGreaterThan(refresh);
    }

    @Test
    void propagatesRemoteBuildFailure() throws Exception {
        RemoteTools tools = remoteTools(23);

        MakeResult result = runDriver(tools, "/tmp/orion-session-host-linux");

        assertThat(result.exitCode()).isEqualTo(23);
        assertThat(result.output()).contains("remote build failed");
        assertExactRemoteArchiveCleanup(tools);
    }

    @Test
    void cleansRemoteArchiveAfterPartialCopyFailure() throws Exception {
        RemoteTools tools = remoteTools("copy-failure", 17, 0);

        MakeResult result = runDriver(tools, "/tmp/orion-session-host-linux");

        assertThat(result.exitCode()).isEqualTo(17);
        assertThat(result.output()).contains("copy failed");
        assertExactRemoteArchiveCleanup(tools);
    }

    @Test
    void rejectsTraversalComponentsBeforeInvokingRemoteTools() throws Exception {
        List<String> unsafeRoots = List.of("/tmp/../escape", "/tmp/./escape");
        for (int index = 0; index < unsafeRoots.size(); index++) {
            RemoteTools tools = remoteTools("unsafe-" + index, 0, 0);

            MakeResult result = runDriver(tools, unsafeRoots.get(index));

            assertThat(result.exitCode()).isEqualTo(64);
            assertThat(result.output()).contains("unsafe SESSION_HOST_LINUX_REMOTE_ROOT");
            assertThat(Files.exists(tools.trace())).isFalse();
        }
    }

    private MakeResult runMake(RemoteTools tools) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "make",
                "--no-print-directory",
                "--silent",
                "session-host-linux-test",
                "SESSION_HOST_LINUX_SSH=" + tools.ssh(),
                "SESSION_HOST_LINUX_SCP=" + tools.scp(),
                "SESSION_HOST_LINUX_HOST=root@example.test",
                "SESSION_HOST_LINUX_PORT=30022",
                "SESSION_HOST_LINUX_REMOTE_ROOT=/tmp/orion-session-host-linux",
                "SESSION_HOST_LINUX_CC=/opt/zig-cc",
                "SESSION_HOST_LINUX_AR=/opt/zig-ar",
                "SESSION_HOST_LINUX_TOOLCHAIN_BIN=/opt/rust/bin"
        ));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true);
        builder.environment().put("ORION_REMOTE_TRACE", tools.trace().toString());
        builder.environment().put("ORION_ARCHIVE_LIST", tools.archiveList().toString());
        builder.environment().put("ORION_REMOTE_COPY_EXIT", Integer.toString(tools.copyExit()));
        builder.environment().put("ORION_REMOTE_BUILD_EXIT", Integer.toString(tools.buildExit()));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new MakeResult(process.waitFor(), output);
    }

    private MakeResult runDriver(RemoteTools tools, String remoteRoot) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "sh", repositoryRoot().resolve("make/session-host-linux-test.sh").toString()
        ).directory(repositoryRoot().toFile()).redirectErrorStream(true);
        builder.environment().put("SESSION_HOST_LINUX_SSH", tools.ssh().toString());
        builder.environment().put("SESSION_HOST_LINUX_SCP", tools.scp().toString());
        builder.environment().put("SESSION_HOST_LINUX_HOST", "root@example.test");
        builder.environment().put("SESSION_HOST_LINUX_PORT", "30022");
        builder.environment().put("SESSION_HOST_LINUX_REMOTE_ROOT", remoteRoot);
        builder.environment().put("SESSION_HOST_LINUX_CC", "/opt/zig-cc");
        builder.environment().put("SESSION_HOST_LINUX_AR", "/opt/zig-ar");
        builder.environment().put("SESSION_HOST_LINUX_TOOLCHAIN_BIN", "/opt/rust/bin");
        builder.environment().put("SESSION_HOST_LINUX_CFLAGS", "--target=x86_64-linux-gnu");
        builder.environment().put("ORION_REMOTE_TRACE", tools.trace().toString());
        builder.environment().put("ORION_ARCHIVE_LIST", tools.archiveList().toString());
        builder.environment().put("ORION_REMOTE_COPY_EXIT", Integer.toString(tools.copyExit()));
        builder.environment().put("ORION_REMOTE_BUILD_EXIT", Integer.toString(tools.buildExit()));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new MakeResult(process.waitFor(), output);
    }

    private RemoteTools remoteTools(int buildExit) throws Exception {
        return remoteTools("remote", 0, buildExit);
    }

    private RemoteTools remoteTools(String name, int copyExit, int buildExit) throws Exception {
        Path ssh = executable(name + "-ssh", """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$ORION_REMOTE_TRACE"
                case "$*" in
                  *"cargo test"*)
                    if [ "$ORION_REMOTE_BUILD_EXIT" -ne 0 ]; then
                      printf '%s\\n' 'remote build failed' >&2
                      exit "$ORION_REMOTE_BUILD_EXIT"
                    fi
                    ;;
                esac
                """);
        Path scp = executable(name + "-scp", """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$ORION_REMOTE_TRACE"
                for argument in "$@"; do
                  if [ -f "$argument" ]; then
                    tar -tzf "$argument" > "$ORION_ARCHIVE_LIST"
                    break
                  fi
                done
                if [ "$ORION_REMOTE_COPY_EXIT" -ne 0 ]; then
                  printf '%s\\n' 'copy failed' >&2
                  exit "$ORION_REMOTE_COPY_EXIT"
                fi
                """);
        return new RemoteTools(
                ssh,
                scp,
                tempDir.resolve("remote.trace"),
                tempDir.resolve("archive.list"),
                copyExit,
                buildExit
        );
    }

    private void assertExactRemoteArchiveCleanup(RemoteTools tools) throws Exception {
        List<String> trace = Files.readAllLines(tools.trace(), StandardCharsets.UTF_8);
        String copy = trace.stream()
                .filter(line -> line.startsWith("-P "))
                .findFirst()
                .orElseThrow();
        String destination = copy.substring(copy.lastIndexOf(' ') + 1);
        String remoteArchive = destination.substring(destination.indexOf(':') + 1);
        assertThat(trace.getLast()).isEqualTo(
                "-T -p 30022 -o BatchMode=yes -o ConnectTimeout=15 "
                        + "-o ServerAliveInterval=15 -o ServerAliveCountMax=12 "
                        + "root@example.test rm -f " + remoteArchive
        );
    }

    private Path executable(String name, String contents) throws Exception {
        Path executable = tempDir.resolve(name);
        Files.writeString(executable, contents, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("Makefile"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }

    private record RemoteTools(
            Path ssh,
            Path scp,
            Path trace,
            Path archiveList,
            int copyExit,
            int buildExit
    ) {
    }

    private record MakeResult(int exitCode, String output) {
    }
}
