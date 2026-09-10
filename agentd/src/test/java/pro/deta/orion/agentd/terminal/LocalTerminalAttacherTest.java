package pro.deta.orion.agentd.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agentd.session.ChildState;
import pro.deta.orion.agentd.session.ControlCommand;
import pro.deta.orion.agentd.session.ControlEndpoint;
import pro.deta.orion.agentd.session.ControlResult;
import pro.deta.orion.agentd.session.HostObservation;
import pro.deta.orion.agentd.session.SessionManifest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTerminalAttacherTest {
    @TempDir
    Path sessionDirectory;

    @Test
    void replaysAnExitedSessionWithoutRequiringALiveControlEndpoint() throws Exception {
        writeJournal(output(1, "retained"), exit(2, 7));
        RecordingTerminal terminal = new RecordingTerminal(new byte[0], new TerminalSize(80, 24));
        AtomicBoolean probed = new AtomicBoolean();
        LocalTerminalAttacher attacher = new LocalTerminalAttacher(
                ignored -> manifest(),
                (directory, manifest) -> {
                    probed.set(true);
                    return HostObservation.unreachable();
                },
                () -> terminal,
                (endpoint, command) -> {
                    throw new AssertionError("exited replay must not send control");
                });

        int result = attacher.attach(sessionDirectory, new PrintStream(new ByteArrayOutputStream()));

        assertThat(result).isEqualTo(7);
        assertThat(terminal.output.toString()).isEqualTo("retained");
        assertThat(terminal.closed).isTrue();
        assertThat(probed).isFalse();
    }

    @Test
    void sendsParsedInputAndCoalescedSizesOnceInOneManualSequenceLane() throws Exception {
        writeJournal(output(1, "ready"));
        byte[] input = new byte[]{'a', 'b', 0x1d, 0x1d, 'c', 0x1d, 'd'};
        RecordingTerminal terminal = new RecordingTerminal(input, new TerminalSize(100, 30));
        terminal.resize = Optional.of(new TerminalSize(120, 40));
        CountDownLatch resized = new CountDownLatch(1);
        terminal.inputGate = resized;
        List<ControlCommand> commands = new ArrayList<>();
        LocalTerminalAttacher attacher = attacher(terminal, (endpoint, command) -> {
            commands.add(command);
            if (command instanceof ControlCommand.Resize value && value.columns() == 120) {
                resized.countDown();
            }
            return new ControlResult.Received(command.operationSequence().orElseThrow());
        });

        int result = attacher.attach(sessionDirectory, new PrintStream(new ByteArrayOutputStream()));

        assertThat(result).isZero();
        assertThat(terminal.output.toString()).isEqualTo("ready");
        assertThat(terminal.closed).isTrue();
        assertThat(commands).isNotEmpty().allSatisfy(command -> {
            assertThat(command).isNotInstanceOf(ControlCommand.Terminate.class);
            if (command instanceof ControlCommand.Input value) {
                assertThat(value.source()).isEqualTo(SessionCommandSource.MANUAL);
                assertThat(value.serverCommandEnvelope()).isEmpty();
                assertThat(value.bytes().toByteArray()).containsExactly('a', 'b', 0x1d, 'c');
            } else if (command instanceof ControlCommand.Resize value) {
                assertThat(value.source()).isEqualTo(SessionCommandSource.MANUAL);
                assertThat(value.serverCommandEnvelope()).isEmpty();
            }
        });
        assertThat(commands.stream().filter(ControlCommand.Input.class::isInstance)).hasSize(1);
        assertThat(commands.stream().map(command -> command.operationSequence().orElseThrow()))
                .doesNotHaveDuplicates().isSorted();
        ControlCommand.Resize lastResize = (ControlCommand.Resize) commands.stream()
                .filter(ControlCommand.Resize.class::isInstance)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(lastResize.columns()).isEqualTo(120);
        assertThat(lastResize.rows()).isEqualTo(40);
    }

    @Test
    void reportsAmbiguousInputOnceAndRestoresTheTerminal() throws Exception {
        writeJournal(output(1, "ready"));
        RecordingTerminal terminal = new RecordingTerminal(new byte[]{'x'}, new TerminalSize(80, 24));
        List<ControlCommand> commands = new ArrayList<>();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        LocalTerminalAttacher attacher = attacher(terminal, (endpoint, command) -> {
            commands.add(command);
            return new ControlResult.Failed(
                    command.operationSequence(), ControlResult.FailureKind.AMBIGUOUS_DELIVERY, "lost response");
        });

        int result = attacher.attach(sessionDirectory, new PrintStream(errors));

        assertThat(result).isEqualTo(1);
        assertThat(commands).hasSize(1);
        assertThat(errors.toString()).contains("ambiguous", "lost response");
        assertThat(terminal.closed).isTrue();
    }

    @Test
    void boundsManifestAndControlDiagnosticsWithoutEchoingInput() throws Exception {
        String oversized = "failure-🚀".repeat(200);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        LocalTerminalAttacher manifestFailure = new LocalTerminalAttacher(
                ignored -> {
                    throw new IOException(oversized);
                },
                (directory, manifest) -> HostObservation.live(ChildState.LIVE),
                () -> {
                    throw new AssertionError("manifest failure must not acquire a terminal");
                },
                (endpoint, command) -> new ControlResult.Received(command.operationSequence().orElseThrow()));

        assertThat(manifestFailure.attach(sessionDirectory, new PrintStream(errors))).isEqualTo(1);
        assertBoundedUtf8(errors.toString());
        assertThat(errors.toString()).startsWith("terminal attach failed: failure-");

        writeJournal(output(1, "ready"));
        errors.reset();
        String secretInput = "secret-input-must-not-be-logged";
        RecordingTerminal terminal = new RecordingTerminal(
                secretInput.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new TerminalSize(80, 24));
        LocalTerminalAttacher controlFailure = attacher(terminal, (endpoint, command) ->
                new ControlResult.Failed(
                        command.operationSequence(),
                        ControlResult.FailureKind.AMBIGUOUS_DELIVERY,
                        oversized));

        assertThat(controlFailure.attach(sessionDirectory, new PrintStream(errors))).isEqualTo(1);
        assertBoundedUtf8(errors.toString());
        assertThat(errors.toString()).startsWith("terminal control failed: ambiguous delivery: failure-");
        assertThat(errors.toString()).doesNotContain(secretInput);
    }

    @Test
    void scansPastTheBoundedLivePreflightForAnExitedUnreachableSession() throws Exception {
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        for (int eventId = 1; eventId <= 256; eventId++) {
            records.write(output(eventId, "x"));
        }
        records.write(exit(257, 7));
        writeJournal(records.toByteArray());
        RecordingTerminal terminal = new RecordingTerminal(new byte[0], new TerminalSize(80, 24));
        AtomicBoolean probed = new AtomicBoolean();
        LocalTerminalAttacher attacher = new LocalTerminalAttacher(
                ignored -> manifest(),
                (directory, manifest) -> {
                    probed.set(true);
                    return HostObservation.unreachable();
                },
                () -> terminal,
                (endpoint, command) -> {
                    throw new AssertionError("exited replay must not send control");
                });

        int result = attacher.attach(sessionDirectory, new PrintStream(new ByteArrayOutputStream()));

        assertThat(result).isEqualTo(7);
        assertThat(probed).isTrue();
        assertThat(terminal.output.toString()).hasSize(256);
        assertThat(terminal.closed).isTrue();
    }

    @Test
    void preflightFailuresDoNotAcquireTheTerminal() throws Exception {
        Files.write(sessionDirectory.resolve("00000001.cbor"), new byte[]{(byte) 0xff});
        AtomicBoolean acquired = new AtomicBoolean();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        LocalTerminalAttacher corrupt = new LocalTerminalAttacher(
                ignored -> manifest(),
                (directory, manifest) -> HostObservation.live(ChildState.LIVE),
                () -> {
                    acquired.set(true);
                    throw new AssertionError();
                },
                (endpoint, command) -> new ControlResult.Received(command.operationSequence().orElseThrow()));

        assertThat(corrupt.attach(sessionDirectory, new PrintStream(errors))).isEqualTo(1);
        assertThat(errors.toString()).contains("journal");
        assertThat(acquired).isFalse();

        writeJournal(output(1, "ready"));
        LocalTerminalAttacher unreachable = new LocalTerminalAttacher(
                ignored -> manifest(),
                (directory, manifest) -> HostObservation.unreachable(),
                () -> {
                    acquired.set(true);
                    throw new AssertionError();
                },
                (endpoint, command) -> new ControlResult.Received(command.operationSequence().orElseThrow()));
        errors.reset();
        assertThat(unreachable.attach(sessionDirectory, new PrintStream(errors))).isEqualTo(1);
        assertThat(errors.toString()).contains("unreachable");
        assertThat(acquired).isFalse();
    }

    private LocalTerminalAttacher attacher(
            RecordingTerminal terminal,
            LocalTerminalAttacher.ControlSender controls
    ) {
        return new LocalTerminalAttacher(
                ignored -> manifest(),
                (directory, manifest) -> HostObservation.live(ChildState.LIVE),
                () -> terminal,
                controls);
    }

    private SessionManifest manifest() {
        return new SessionManifest(
                1, 1, 4, "session", 1, 2, List.of("/bin/sh"), sessionDirectory.toString(),
                100, OptionalLong.of(101), 80, 24, 80, 24, "xterm", sandbox(),
                new ControlEndpoint(
                        ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                        "control.sock",
                        sessionDirectory.resolve("control.sock")));
    }

    private static SessionManifest.Sandbox sandbox() {
        return new SessionManifest.Sandbox(false, "none", "fail", List.of(), List.of());
    }

    private void writeJournal(byte[]... records) throws IOException {
        ByteArrayOutputStream journal = new ByteArrayOutputStream();
        for (byte[] record : records) {
            journal.write(record);
        }
        Files.write(sessionDirectory.resolve("00000001.cbor"), journal.toByteArray());
    }

    private static void assertBoundedUtf8(String value) {
        assertThat(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(513);
        assertThat(value).doesNotEndWith("�\n");
    }

    private static byte[] output(long id, String text) throws Exception {
        return codec().encode(new EventId(id), new SessionEventPayload.PtyOutput(
                ProtocolBytes.copyOf(text.getBytes())));
    }

    private static byte[] exit(long id, int code) throws Exception {
        return codec().encode(new EventId(id), new SessionEventPayload.ProcessExited(code));
    }

    private static SessionEventCodec codec() {
        return new SessionEventCodec(AgentProtocolLimits.journalDefaults());
    }

    private static final class RecordingTerminal implements TerminalDevice {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final TerminalSize size;
        private Optional<TerminalSize> resize = Optional.empty();
        private CountDownLatch inputGate;
        private boolean closed;

        private RecordingTerminal(byte[] input, TerminalSize size) {
            ByteArrayInputStream bytes = new ByteArrayInputStream(input);
            this.input = new InputStream() {
                @Override
                public int read() throws IOException {
                    awaitResizeIfNeeded();
                    return bytes.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    awaitResizeIfNeeded();
                    return bytes.read(buffer, offset, length);
                }

                private void awaitResizeIfNeeded() throws IOException {
                    if (inputGate == null) {
                        return;
                    }
                    try {
                        if (!inputGate.await(1, TimeUnit.SECONDS)) {
                            throw new IOException("resize was not delivered");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("input interrupted", exception);
                    }
                }
            };
            this.size = size;
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public TerminalSize size() {
            return size;
        }

        @Override
        public Optional<TerminalSize> awaitResize(Duration interval) throws InterruptedException {
            Optional<TerminalSize> result = resize;
            resize = Optional.empty();
            Thread.sleep(1);
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
