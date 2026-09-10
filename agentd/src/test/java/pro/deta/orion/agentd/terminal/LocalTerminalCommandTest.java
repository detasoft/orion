package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTerminalCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesAnExplicitSessionDirectoryForAttach() {
        AtomicReference<Path> attached = new AtomicReference<>();
        Path requested = temporaryDirectory.resolve("sessions/../session-one");

        int result = new LocalTerminalCommand((directory, errors) -> {
            attached.set(directory);
            return 7;
        }).execute(
                new String[]{"attach", "--session-dir", requested.toString()},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(result).isEqualTo(7);
        assertThat(attached.get()).isEqualTo(requested.toAbsolutePath().normalize());
    }

    @Test
    void rejectsMalformedAttachOptionsBeforeAcquiringTheTerminal() {
        AtomicReference<Path> attached = new AtomicReference<>();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int missing = runAttach(new String[]{"attach"}, errors, attached);
        int duplicate = runAttach(new String[]{
                "attach", "--session-dir", temporaryDirectory.toString(),
                "--session-dir", temporaryDirectory.toString()
        }, errors, attached);
        int unknown = runAttach(new String[]{"attach", "--state-dir", temporaryDirectory.toString()},
                errors, attached);

        assertThat(missing).isEqualTo(2);
        assertThat(duplicate).isEqualTo(2);
        assertThat(unknown).isEqualTo(2);
        assertThat(attached.get()).isNull();
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("--session-dir", "Usage:");
    }

    private static int runAttach(
            String[] arguments,
            ByteArrayOutputStream errors,
            AtomicReference<Path> attached
    ) {
        return new LocalTerminalCommand((directory, attachErrors) -> {
            attached.set(directory);
            return 0;
        }).execute(
                arguments,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors));
    }
}
