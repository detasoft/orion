package pro.deta.orion.agentd;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import pro.deta.orion.agentd.core.AgentLaunchContext;

import static org.assertj.core.api.Assertions.assertThat;

class AgentdMainTest {
    @Test
    void printsHelpWithoutStartingTheAgent() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = AgentdMain.run(
                new String[]{"--help"},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Usage:", "--server");
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void reportsInvalidConfiguration() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = AgentdMain.run(
                new String[]{"--server", "http://insecure.test"},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("HTTPS", "Usage:");
    }

    @Test
    void helpAndInvalidConfigurationDoNotReadStandardInput() {
        InputStream forbidden = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("stdin must not be read");
            }
        };

        assertThat(run(new String[]{"--help"}, forbidden, (configuration, context) -> { })).isZero();
        assertThat(run(new String[]{"--server", "http://bad"}, forbidden,
                (configuration, context) -> { })).isEqualTo(2);
    }

    @Test
    void readsPermitFromStdinAndPassesLaunchContextToLauncher() {
        AtomicReference<AgentLaunchContext> launched = new AtomicReference<>();
        byte[] permit = new byte[32];
        String input = Base64.getUrlEncoder().withoutPadding().encodeToString(permit) + "\n";

        int exit = run(validArguments(), new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)),
                (configuration, context) -> launched.set(context));

        assertThat(exit).isZero();
        assertThat(launched.get().agentId().value()).isEqualTo("agent-1");
    }

    @Test
    void invalidPermitAndStartupFailureAreRedacted() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int invalid = AgentdMain.run(
                validArguments(),
                new ByteArrayInputStream("permit-secret\n".getBytes(StandardCharsets.US_ASCII)),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors),
                (configuration, context) -> { });

        assertThat(invalid).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).doesNotContain("permit-secret");

        errors.reset();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]) + "\n";
        int failed = AgentdMain.run(
                validArguments(),
                new ByteArrayInputStream(encoded.getBytes(StandardCharsets.US_ASCII)),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors),
                (configuration, context) -> {
                    throw new IllegalStateException("startup failed");
                });
        assertThat(failed).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8)).doesNotContain(encoded.strip());
    }

    private static int run(String[] args, InputStream input, AgentdMain.Launcher launcher) {
        return AgentdMain.run(
                args, input, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), launcher);
    }

    private static String[] validArguments() {
        return new String[]{
                "--server", "https://agent.test",
                "--state-dir", "target/state",
                "--agent-id", "agent-1",
                "--generation", "1",
                "--launch-id", "10010203-0405-0607-0809-0a0b0c0d0e0f",
                "--agent-version", "1.0.0"
        };
    }
}
