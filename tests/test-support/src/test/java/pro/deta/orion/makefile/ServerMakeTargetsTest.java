package pro.deta.orion.makefile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerMakeTargetsTest {

    @TempDir
    Path tempDir;

    @Test
    void clonesPositionalRepositoryWithBearerHeader() throws Exception {
        Path capture = tempDir.resolve("git-invocation");
        Path bin = Files.createDirectory(tempDir.resolve("git-bin"));
        createExecutable(bin.resolve("git"), """
                #!/bin/sh
                {
                    printf 'header=%s\\n' "$ORION_AUTH_HEADER"
                    printf 'arg=%s\\n' "$@"
                } > "$CAPTURE_FILE"
                """);

        ProcessBuilder builder = make(
                "clone-http-repo",
                "team/repository",
                "ISSUE_TOKEN_COMMAND=printf test-token"
        );
        configureFakeCommand(builder, bin, capture);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(Files.readAllLines(capture)).containsExactly(
                "header=Authorization: Bearer test-token",
                "arg=--config-env=http.extraHeader=ORION_AUTH_HEADER",
                "arg=clone",
                "arg=http://localhost:8000/r/team/repository"
        );
    }

    @Test
    void rejectsMissingRepositoryName() throws Exception {
        Process process = make("clone-http-repo").start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isNotZero();
        assertThat(output).contains("Usage: make clone-http-repo <repository>");
    }

    @Test
    void listsRepositoriesThroughSshCommand() throws Exception {
        Path capture = tempDir.resolve("ssh-invocation");
        Path bin = Files.createDirectory(tempDir.resolve("ssh-bin"));
        createExecutable(bin.resolve("ssh"), """
                #!/bin/sh
                printf 'arg=%s\\n' "$@" > "$CAPTURE_FILE"
                printf 'orion\\nteam/repository\\n'
                """);

        ProcessBuilder builder = make("list-repos");
        configureFakeCommand(builder, bin, capture);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).isEqualTo("orion\nteam/repository\n");
        assertThat(Files.readAllLines(capture)).endsWith(
                "arg=-l",
                "arg=root",
                "arg=localhost",
                "arg=repositories"
        );
    }

    @Test
    void generatesMissingAdminIdentity() throws Exception {
        Path capture = tempDir.resolve("ssh-keygen-invocation");
        Path bin = Files.createDirectory(tempDir.resolve("ssh-keygen-bin"));
        createExecutable(bin.resolve("ssh-keygen"), """
                #!/bin/sh
                printf 'arg=%s\n' "$@" > "$CAPTURE_FILE"
                while [ "$#" -gt 0 ]; do
                    if [ "$1" = '-f' ]; then
                        touch "$2" "$2.pub"
                        break
                    fi
                    shift
                done
                """);
        Path identity = tempDir.resolve("orion/admin-identity.pem");
        ProcessBuilder builder = make("admin-key", "ORION_SSH_KEY=" + identity);
        configureFakeCommand(builder, bin, capture);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(Files.readAllLines(capture)).containsExactly(
                "arg=-q",
                "arg=-t",
                "arg=ed25519",
                "arg=-N",
                "arg=",
                "arg=-f",
                "arg=" + identity);
    }

    @Test
    void enrollsGeneratedAdminIdentityAndVerifiesIt() throws Exception {
        Path capture = tempDir.resolve("ssh-enrollment");
        Path bin = Files.createDirectory(tempDir.resolve("ssh-enrollment-bin"));
        Path identity = Files.createFile(tempDir.resolve("admin-identity.pem"));
        createExecutable(bin.resolve("ssh"), """
                #!/bin/sh
                count_file="$CAPTURE_FILE.count"
                count=0
                if [ -f "$count_file" ]; then
                    count=$(cat "$count_file")
                fi
                count=$((count + 1))
                printf '%s\n' "$count" > "$count_file"
                {
                    printf 'call=%s\n' "$count"
                    printf 'arg=%s\n' "$@"
                    if [ "$count" -eq 1 ]; then
                        printf 'token=%s\n' "$("$SSH_ASKPASS" 'Enrollment token: ')"
                        printf 'keys=%s\n' "$("$SSH_ASKPASS" 'Keys (`all`, numbers, or OpenSSH key): ')"
                    fi
                } >> "$CAPTURE_FILE"
                if [ "$count" -eq 1 ]; then
                    exit 255
                fi
                printf 'orion: RUNNING\n'
                """);
        ProcessBuilder builder = make(
                "enroll-admin-key",
                "ORION_SSH_KEY=" + identity,
                "ORION_SSH_ENROLLMENT_TOKEN=test-enrollment-token");
        configureFakeCommand(builder, bin, capture);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("Admin SSH key enrolled: " + identity);
        assertThat(Files.readString(capture))
                .contains("call=1", "token=test-enrollment-token", "keys=all", "call=2")
                .contains("arg=" + identity);
    }

    @Test
    void runServerRequiresKeyMaterialPassword() throws Exception {
        Path identity = Files.createFile(tempDir.resolve("admin-identity.pem"));

        Process process = make(
                "run-server",
                "ORION_SSH_KEY=" + identity,
                "ORION_KEY_MATERIAL_PASSWORD=").start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isNotZero();
        assertThat(output).contains("ORION_KEY_MATERIAL_PASSWORD is required");
    }

    @Test
    void runServerPassesKeyMaterialPasswordOnlyThroughEnvironment() throws Exception {
        String password = "store-\"password-$(must-not-run);-$HOME";
        Path capture = tempDir.resolve("maven-environment");
        Path bin = Files.createDirectory(tempDir.resolve("maven-bin"));
        Path identity = Files.createFile(tempDir.resolve("admin-identity.pem"));
        createExecutable(bin.resolve("mvn"), """
                #!/bin/sh
                printf '%s' "$ORION_KEY_MATERIAL_PASSWORD" > "$CAPTURE_FILE"
                """);
        ProcessBuilder builder = make(
                "run-server",
                "ORION_SSH_KEY=" + identity,
                "MAVEN=mvn");
        configureFakeCommand(builder, bin, capture);
        builder.environment().put("ORION_KEY_MATERIAL_PASSWORD", password);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(Files.readString(capture)).isEqualTo(password);
    }

    private void configureFakeCommand(ProcessBuilder builder, Path bin, Path capture) {
        builder.environment().put("CAPTURE_FILE", capture.toString());
        builder.environment().put(
                "PATH",
                bin + File.pathSeparator + builder.environment().get("PATH")
        );
    }

    private ProcessBuilder make(String... arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add("make");
        command.add("--no-print-directory");
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command)
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("make/server.mk"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }

    private void createExecutable(Path executable, String script) throws IOException {
        Files.writeString(executable, script);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    }
}
