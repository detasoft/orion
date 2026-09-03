package pro.deta.orion.provisioning;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.SshClientKeyCapability;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshKeyEnrollerTest {
    @Test
    void enrollsWithPasswordThenVerifiesInFreshPublicKeySession(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        BootstrapPassword password = BootstrapPassword.copyAndClear("bootstrap-secret".toCharArray());
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
        try {
            try (TestSshServer server = TestSshServer.startEnrollable(root, host, "bootstrap-secret")) {
                new SshKeyEnroller().enroll(
                        server.endpoint(), capability(client), Optional.of(password), options());

                String expected = PublicKeyEntry.toString(client.getPublic()) + System.lineSeparator();
                Path sshDirectory = root.resolve(".ssh");
                Path authorizedKeys = sshDirectory.resolve("authorized_keys");
                assertThat(Files.readString(authorizedKeys, StandardCharsets.UTF_8)).isEqualTo(expected);
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(sshDirectory)))
                        .isEqualTo("rwx------");
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(authorizedKeys)))
                        .isEqualTo("rw-------");
                assertThat(server.passwordAttempts()).isEqualTo(1);
                assertThat(new HashSet<>(server.publicKeySessions())).hasSize(2);
                assertThat(new HashSet<>(server.passwordSessions())).hasSize(1);
                assertThat(server.publicKeySessions()).doesNotContainAnyElementsOf(server.passwordSessions());
                assertThat(server.commands())
                        .hasSize(1)
                        .noneMatch(command -> command.contains("bootstrap-secret"));
                assertThat(server.commandInputs())
                        .hasSize(1)
                        .allSatisfy(input -> assertThat(new String(input, StandardCharsets.UTF_8))
                                .doesNotContain("bootstrap-secret"));
                assertThat(password.isCleared()).isTrue();
                assertThat(password.toString()).doesNotContain("bootstrap-secret");
            }
        } finally {
            rootLogger.detachAppender(logs);
            logs.stop();
        }
        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("bootstrap-secret"));
        assertThat(allRegularFileText(root)).doesNotContain("bootstrap-secret");
    }

    @Test
    void preservesExistingContentAndPermissionsAndRepeatEnrollmentIsIdempotent(@TempDir Path root)
            throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        KeyPair unrelated = keyPair();
        Path sshDirectory = Files.createDirectory(root.resolve(".ssh"));
        Path authorizedKeys = sshDirectory.resolve("authorized_keys");
        String existing = "# keep this comment\n" + PublicKeyEntry.toString(unrelated.getPublic());
        Files.writeString(authorizedKeys, existing, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(sshDirectory, PosixFilePermissions.fromString("rwxr-x---"));
        Files.setPosixFilePermissions(authorizedKeys, PosixFilePermissions.fromString("rw-r-----"));
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "bootstrap-secret")) {
            BootstrapPassword firstPassword = password("bootstrap-secret");
            new SshKeyEnroller().enroll(
                    server.endpoint(), capability(client), Optional.of(firstPassword), options());
            String afterFirst = Files.readString(authorizedKeys, StandardCharsets.UTF_8);
            BootstrapPassword unusedPassword = password("must-not-be-used");

            new SshKeyEnroller().enroll(
                    server.endpoint(), capability(client), Optional.of(unusedPassword), options());

            assertThat(afterFirst).isEqualTo(
                    existing + "\n" + PublicKeyEntry.toString(client.getPublic()) + "\n");
            assertThat(Files.readString(authorizedKeys, StandardCharsets.UTF_8)).isEqualTo(afterFirst);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(sshDirectory)))
                    .isEqualTo("rwxr-x---");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(authorizedKeys)))
                    .isEqualTo("rw-r-----");
            assertThat(server.commands()).hasSize(1);
            assertThat(server.passwordAttempts()).isEqualTo(1);
            assertThat(firstPassword.isCleared()).isTrue();
            assertThat(unusedPassword.isCleared()).isTrue();
        }
    }

    @Test
    void ignoresKeyTokensInAnUnrelatedQuotedOptionAndAppendsTheSelectedKey(@TempDir Path root)
            throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        KeyPair unrelated = keyPair();
        String selected = PublicKeyEntry.toString(client.getPublic());
        String selectedBlob = selected.substring(selected.indexOf(' ') + 1);
        String unrelatedLine = "command=\"note ssh-rsa " + selectedBlob + " trailing\",no-pty "
                + PublicKeyEntry.toString(unrelated.getPublic()) + " unrelated comment";
        String existing = "# preserve exactly\n" + unrelatedLine;
        Path sshDirectory = Files.createDirectory(root.resolve(".ssh"));
        Path authorizedKeys = Files.writeString(
                sshDirectory.resolve("authorized_keys"), existing, StandardCharsets.UTF_8);
        BootstrapPassword password = password("bootstrap-secret");

        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "bootstrap-secret")) {
            new SshKeyEnroller().enroll(
                    server.endpoint(), capability(client), Optional.of(password), options());

            assertThat(new HashSet<>(server.publicKeySessions())).hasSize(2);
            assertThat(server.passwordAttempts()).isEqualTo(1);
            assertThat(server.commands()).hasSize(1);
            assertThat(Files.readString(authorizedKeys, StandardCharsets.UTF_8))
                    .isEqualTo(existing + "\n" + selected + "\n");
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void acceptsAnExistingKeyWithoutBootstrapPassword(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        Path sshDirectory = Files.createDirectory(root.resolve(".ssh"));
        Files.writeString(
                sshDirectory.resolve("authorized_keys"),
                "command=\"printf existing key\",no-pty "
                        + PublicKeyEntry.toString(client.getPublic()) + " retained comment\n",
                StandardCharsets.UTF_8);
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "unused")) {
            new SshKeyEnroller().enroll(
                    server.endpoint(), capability(client), Optional.empty(), options());

            assertThat(server.passwordAttempts()).isZero();
            assertThat(server.commands()).isEmpty();
        }
    }

    @Test
    void passwordRetryDoesNotAppendAnOptionBearingSelectedKeyAgain(@TempDir Path root)
            throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        String existing = "command=\"printf selected key\",no-pty "
                + PublicKeyEntry.toString(client.getPublic()) + " retained comment\n";
        Path sshDirectory = Files.createDirectory(root.resolve(".ssh"));
        Path authorizedKeys = Files.writeString(
                sshDirectory.resolve("authorized_keys"), existing, StandardCharsets.UTF_8);
        BootstrapPassword password = password("bootstrap-secret");

        try (TestSshServer server = TestSshServer.startEnrollableRejectingFirstPublicKeySession(
                root, host, "bootstrap-secret")) {
            new SshKeyEnroller().enroll(
                    server.endpoint(), capability(client), Optional.of(password), options());

            assertThat(new HashSet<>(server.publicKeySessions())).hasSize(2);
            assertThat(server.passwordAttempts()).isEqualTo(1);
            assertThat(server.commands()).hasSize(1);
            assertThat(Files.readString(authorizedKeys, StandardCharsets.UTF_8)).isEqualTo(existing);
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void reportsMissingBootstrapPasswordWithoutMutatingRemoteState(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "unused")) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(keyPair()), Optional.empty(), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .extracting(error -> ((SshKeyEnrollmentException) error).failure())
                    .isEqualTo(EnrollmentFailure.BOOTSTRAP_PASSWORD_REQUIRED);

            assertThat(server.passwordAttempts()).isZero();
            assertThat(server.commands()).isEmpty();
            assertThat(root.resolve(".ssh")).doesNotExist();
        }
    }

    @Test
    void wrongPasswordMakesOneAttemptWithoutMutationAndIsAbsentFromDiagnostics(@TempDir Path root)
            throws Exception {
        KeyPair host = keyPair();
        BootstrapPassword password = password("wrong-secret");
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "expected-secret")) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(keyPair()), Optional.of(password), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .satisfies(error -> {
                        SshKeyEnrollmentException enrollment = (SshKeyEnrollmentException) error;
                        assertThat(enrollment.failure()).isEqualTo(EnrollmentFailure.AUTHENTICATION);
                        assertThat(enrollment.toString()).doesNotContain("wrong-secret");
                        assertThat(enrollment.getCause()).isNull();
                    });

            assertThat(server.passwordAttempts()).isEqualTo(1);
            assertThat(server.commands()).noneMatch(command -> command.contains("wrong-secret"));
            assertThat(root.resolve(".ssh")).doesNotExist();
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void hostKeyMismatchDoesNotFallBackToPassword(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        BootstrapPassword password = password("bootstrap-secret");
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "bootstrap-secret")) {
            SshEndpoint wrongEndpoint = new SshEndpoint(
                    server.endpoint().host(), server.endpoint().port(),
                    server.endpoint().username(), keyPair().getPublic());

            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    wrongEndpoint, capability(keyPair()), Optional.of(password), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .extracting(error -> ((SshKeyEnrollmentException) error).failure())
                    .isEqualTo(EnrollmentFailure.HOST_IDENTITY);

            assertThat(server.passwordAttempts()).isZero();
            assertThat(server.commands()).isEmpty();
            assertThat(root.resolve(".ssh")).doesNotExist();
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void authenticationTimeoutDoesNotFallBackToPassword(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        BootstrapPassword password = password("bootstrap-secret");
        ProvisioningOptions shortAuthentication = new ProvisioningOptions(
                Duration.ofSeconds(1), Duration.ofMillis(50),
                Duration.ofSeconds(1), Duration.ofSeconds(5));
        try (TestSshServer server = TestSshServer.start(
                root, host, keyPair(), Duration.ofMillis(250), Duration.ZERO)) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(keyPair()),
                    Optional.of(password), shortAuthentication))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .extracting(error -> ((SshKeyEnrollmentException) error).failure())
                    .isEqualTo(EnrollmentFailure.TIMEOUT);

            assertThat(server.passwordAttempts()).isZero();
            assertThat(server.commands()).isEmpty();
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void rejectsSymlinkedSshDirectoryAsUnsafeRemoteState(@TempDir Path root) throws Exception {
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Files.createSymbolicLink(root.resolve(".ssh"), elsewhere);
        KeyPair host = keyPair();
        BootstrapPassword password = password("bootstrap-secret");
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "bootstrap-secret")) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(keyPair()), Optional.of(password), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .extracting(error -> ((SshKeyEnrollmentException) error).failure())
                    .isEqualTo(EnrollmentFailure.UNSAFE_REMOTE_STATE);

            try (var contents = Files.list(elsewhere)) {
                assertThat(contents).isEmpty();
            }
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void rejectsSymlinkedAuthorizedKeysWithoutMutatingItsTarget(@TempDir Path root) throws Exception {
        Path sshDirectory = Files.createDirectory(root.resolve(".ssh"));
        Path target = Files.writeString(
                root.resolve("external-authorized-keys"), "# external\n", StandardCharsets.UTF_8);
        Files.createSymbolicLink(sshDirectory.resolve("authorized_keys"), target);
        KeyPair host = keyPair();
        BootstrapPassword password = password("bootstrap-secret");
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "bootstrap-secret")) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(keyPair()), Optional.of(password), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .satisfies(error -> {
                        SshKeyEnrollmentException enrollment = (SshKeyEnrollmentException) error;
                        assertThat(enrollment.failure()).isEqualTo(EnrollmentFailure.UNSAFE_REMOTE_STATE);
                        assertThat(enrollment.getMessage()).contains("authorized_keys");
                    });

            assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("# external\n");
            assertThat(Files.isSymbolicLink(sshDirectory.resolve("authorized_keys"))).isTrue();
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void classifiesRejectedGeneratedKeyInputAsKeyMaterialFailure(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        BootstrapPassword password = password("bootstrap-secret");
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "bootstrap-secret")) {
            assertThatThrownBy(() -> SshKeyEnroller.withKeyFormatterForTest(
                    key -> "malformed generated input").enroll(
                    server.endpoint(), capability(keyPair()), Optional.of(password), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .satisfies(error -> {
                        SshKeyEnrollmentException enrollment = (SshKeyEnrollmentException) error;
                        assertThat(enrollment.failure()).isEqualTo(EnrollmentFailure.KEY_MATERIAL);
                        assertThat(enrollment.getMessage()).contains("Generated SSH public key");
                    });

            assertThat(root.resolve(".ssh")).doesNotExist();
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void reportsWriteFailureWithoutDisclosingPassword(@TempDir Path root) throws Exception {
        Path sshDirectory = Files.createDirectory(root.resolve(".ssh"));
        Path authorizedKeys = Files.writeString(
                sshDirectory.resolve("authorized_keys"), "# existing\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(sshDirectory, PosixFilePermissions.fromString("r-x------"));
        Files.setPosixFilePermissions(authorizedKeys, PosixFilePermissions.fromString("r--------"));
        KeyPair host = keyPair();
        BootstrapPassword password = password("write-failure-secret");
        try (TestSshServer server = TestSshServer.startEnrollable(root, host, "write-failure-secret")) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(keyPair()), Optional.of(password), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .satisfies(error -> {
                        SshKeyEnrollmentException enrollment = (SshKeyEnrollmentException) error;
                        assertThat(enrollment.failure()).isEqualTo(EnrollmentFailure.REMOTE_WRITE);
                        assertThat(enrollment.toString()).doesNotContain("write-failure-secret");
                    });

            assertThat(Files.readString(authorizedKeys, StandardCharsets.UTF_8)).isEqualTo("# existing\n");
            assertThat(server.commands()).noneMatch(command -> command.contains("write-failure-secret"));
            assertThat(server.commandInputs())
                    .allSatisfy(input -> assertThat(new String(input, StandardCharsets.UTF_8))
                            .doesNotContain("write-failure-secret"));
            assertThat(password.isCleared()).isTrue();
        } finally {
            Files.setPosixFilePermissions(sshDirectory, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(authorizedKeys, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void reportsVerificationFailureAndLeavesAppendedKeyForDiagnosis(@TempDir Path root)
            throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        BootstrapPassword password = password("bootstrap-secret");
        try (TestSshServer server = TestSshServer.startEnrollableRejectingVerification(
                root, host, "bootstrap-secret")) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(client), Optional.of(password), options()))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .extracting(error -> ((SshKeyEnrollmentException) error).failure())
                    .isEqualTo(EnrollmentFailure.VERIFICATION);

            assertThat(Files.readString(root.resolve(".ssh/authorized_keys"), StandardCharsets.UTF_8))
                    .contains(PublicKeyEntry.toString(client.getPublic()));
            assertThat(server.passwordAttempts()).isEqualTo(1);
            assertThat(new HashSet<>(server.publicKeySessions())).hasSize(2);
            assertThat(password.isCleared()).isTrue();
        }
    }

    @Test
    void preservesTimeoutClassificationDuringFreshKeyVerification(@TempDir Path root)
            throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        BootstrapPassword password = password("bootstrap-secret");
        ProvisioningOptions shortAuthentication = new ProvisioningOptions(
                Duration.ofSeconds(1), Duration.ofMillis(500),
                Duration.ofSeconds(1), Duration.ofSeconds(5));
        try (TestSshServer server = TestSshServer.startEnrollableWithVerificationDelay(
                root, host, "bootstrap-secret", Duration.ofMillis(1500))) {
            assertThatThrownBy(() -> new SshKeyEnroller().enroll(
                    server.endpoint(), capability(client),
                    Optional.of(password), shortAuthentication))
                    .isInstanceOf(SshKeyEnrollmentException.class)
                    .extracting(error -> ((SshKeyEnrollmentException) error).failure())
                    .isEqualTo(EnrollmentFailure.TIMEOUT);

            assertThat(Files.readString(root.resolve(".ssh/authorized_keys"), StandardCharsets.UTF_8))
                    .contains(PublicKeyEntry.toString(client.getPublic()));
            assertThat(server.passwordAttempts()).isEqualTo(1);
            assertThat(password.isCleared()).isTrue();
        }
    }

    private static SshClientKeyCapability capability(KeyPair keyPair) {
        return new SshClientKeyCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return null;
            }

            @Override
            public KeyPair keyPair() {
                return keyPair;
            }
        };
    }

    private static ProvisioningOptions options() {
        return new ProvisioningOptions(
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private static BootstrapPassword password(String value) {
        return BootstrapPassword.copyAndClear(value.toCharArray());
    }

    private static String allRegularFileText(Path root) throws Exception {
        StringBuilder content = new StringBuilder();
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path)) {
                    content.append(Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        return content.toString();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
