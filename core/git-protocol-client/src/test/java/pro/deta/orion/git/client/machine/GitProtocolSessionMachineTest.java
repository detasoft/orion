package pro.deta.orion.git.client.machine;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.client.GitProtocolService;
import pro.deta.orion.git.client.GitProtocolTransportException;
import pro.deta.orion.git.client.GitProtocolTransportOptions;
import pro.deta.orion.git.client.ScriptedGitProtocolTransport;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitProtocolSessionMachineTest {
    private static final URI REMOTE = URI.create("https://example.test/repo.git");
    private static final GitProtocolTransportOptions OPTIONS = new GitProtocolTransportOptions(
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(5), 65_520, 1024);

    // --------------------------------------------------------------------------------------------
    // Simple configurable test machine:
    //   emit one Write, then consume N reads concatenating them into a string, then Complete.
    // If endOfInput() is called while waiting for a read, it stores the fact for assertion.
    // --------------------------------------------------------------------------------------------
    private static final class StringCatMachine implements GitClientMachine<String> {
        private final byte[] request;
        private final int expectedReads;
        private final StringBuilder received = new StringBuilder();
        private int readsRemaining;
        private boolean written;
        private boolean complete;
        private boolean endOfInputCalled;
        private int closeCalls;

        StringCatMachine(byte[] request, int expectedReads) {
            this.request = request;
            this.readsRemaining = expectedReads;
            this.expectedReads = expectedReads;
        }

        @Override
        public GitProtocolService service() {
            return GitProtocolService.UPLOAD_PACK;
        }

        @Override
        public GitClientAction<String> action() {
            if (!written) {
                return new GitClientAction.Write<>(Unpooled.copiedBuffer(request));
            }
            if (readsRemaining > 0) {
                return new GitClientAction.Read<>();
            }
            complete = true;
            return new GitClientAction.Complete<>(received.toString());
        }

        @Override
        public void written() {
            assertThat(written).isFalse();
            written = true;
        }

        @Override
        public boolean accept(ByteBuf input) {
            assertThat(readsRemaining).isGreaterThan(0);
            received.append(input.toString(StandardCharsets.UTF_8));
            readsRemaining--;
            return true;
        }

        @Override
        public void endOfInput() {
            endOfInputCalled = true;
            readsRemaining = 0; // stop the loop
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    // Machine that immediately emits a Fail action.
    private static final class FailingMachine implements GitClientMachine<String> {
        private final GitProtocolClientException failure;
        private int closeCalls;

        FailingMachine(GitProtocolClientException failure) {
            this.failure = failure;
        }

        @Override
        public GitProtocolService service() {
            return GitProtocolService.RECEIVE_PACK;
        }

        @Override
        public GitClientAction<String> action() {
            return new GitClientAction.Fail<>(failure);
        }

        @Override
        public void written() {}

        @Override
        public boolean accept(ByteBuf input) { return true; }

        @Override
        public void endOfInput() {}

        @Override
        public void close() { closeCalls++; }
    }

    // --------------------------------------------------------------------------------------------

    @Test
    void happyPath_writeThenTwoFragmentedReadsThenComplete() throws Exception {
        byte[] req = "git-request".getBytes(StandardCharsets.UTF_8);
        byte[] chunk1 = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = " world".getBytes(StandardCharsets.UTF_8);

        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(req), List.of(chunk1, chunk2));
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        StringCatMachine machine = new StringCatMachine(req, 2);

        String result = runner.run(REMOTE, OPTIONS, machine);

        assertThat(result).isEqualTo("hello world");
        assertThat(transport.openedService()).isEqualTo(GitProtocolService.UPLOAD_PACK);
        assertThat(transport.openedUri()).isEqualTo(REMOTE);
        assertThat(transport.openedOptions()).isSameAs(OPTIONS);
        assertThat(transport.closed()).isTrue();
        assertThat(transport.closeCalls()).isOne();
        assertThat(machine.closeCalls).isOne();
    }

    @Test
    void openFailure_closesClientMachineButNotSession() {
        GitProtocolTransportException openEx = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.OPEN, true, "Scripted open failure");
        ScriptedGitProtocolTransport transport = new ScriptedGitProtocolTransport(
                List.of(), List.of(), openEx, null, null);
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        StringCatMachine machine = new StringCatMachine(new byte[0], 0);

        assertThatThrownBy(() -> runner.run(REMOTE, OPTIONS, machine))
                .isSameAs(openEx);

        assertThat(machine.closeCalls).isOne();
        assertThat(transport.closeCalls()).isZero();
    }

    @Test
    void writeFailure_closesBothMachines() {
        byte[] req = "request".getBytes(StandardCharsets.UTF_8);
        GitProtocolTransportException writeEx = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.WRITE, false, "Scripted write failure");
        ScriptedGitProtocolTransport transport = new ScriptedGitProtocolTransport(
                List.of(), List.of(), null, writeEx, null);
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        StringCatMachine machine = new StringCatMachine(req, 1);

        assertThatThrownBy(() -> runner.run(REMOTE, OPTIONS, machine))
                .isSameAs(writeEx);

        assertThat(machine.closeCalls).isOne();
        assertThat(transport.closed()).isTrue();
    }

    @Test
    void readFailure_closesBothMachines() {
        byte[] req = "request".getBytes(StandardCharsets.UTF_8);
        GitProtocolTransportException readEx = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.READ, true, "Scripted read failure");
        ScriptedGitProtocolTransport transport = new ScriptedGitProtocolTransport(
                List.of(req), List.of(), readEx);
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        StringCatMachine machine = new StringCatMachine(req, 1);

        assertThatThrownBy(() -> runner.run(REMOTE, OPTIONS, machine))
                .isSameAs(readEx);

        assertThat(machine.closeCalls).isOne();
        assertThat(transport.closed()).isTrue();
    }

    @Test
    void clientFailAction_closesBothMachines() {
        GitProtocolClientException clientEx = new GitProtocolClientException(
                GitProtocolClientException.Operation.SESSION, "Scripted client failure");
        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(), List.of());
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        FailingMachine machine = new FailingMachine(clientEx);

        assertThatThrownBy(() -> runner.run(REMOTE, OPTIONS, machine))
                .isSameAs(clientEx);

        assertThat(machine.closeCalls).isOne();
        assertThat(transport.closed()).isTrue();
    }

    @Test
    void closeFailure_becomesPrimaryWhenExchangeSucceeded() {
        byte[] req = "req".getBytes(StandardCharsets.UTF_8);
        byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
        GitProtocolTransportException closeEx = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.CLOSE, false, "Scripted close failure");
        ScriptedGitProtocolTransport transport = new ScriptedGitProtocolTransport(
                List.of(req), List.of(resp), null, null, null, closeEx);
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        StringCatMachine machine = new StringCatMachine(req, 1);

        assertThatThrownBy(() -> runner.run(REMOTE, OPTIONS, machine))
                .isSameAs(closeEx);

        assertThat(machine.closeCalls).isOne();
    }

    @Test
    void closeFailure_isSuppressedWhenExchangeAlreadyFailed() {
        byte[] req = "req".getBytes(StandardCharsets.UTF_8);
        GitProtocolTransportException readEx = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.READ, false, "Read failure");
        GitProtocolTransportException closeEx = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.CLOSE, false, "Close failure");
        ScriptedGitProtocolTransport transport = new ScriptedGitProtocolTransport(
                List.of(req), List.of(), null, null, readEx, closeEx);
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        StringCatMachine machine = new StringCatMachine(req, 1);

        assertThatThrownBy(() -> runner.run(REMOTE, OPTIONS, machine))
                .isSameAs(readEx)
                .satisfies(e -> assertThat(e.getSuppressed()).contains(closeEx));
    }

    @Test
    void nullReadCallsEndOfInput() throws Exception {
        byte[] req = "req".getBytes(StandardCharsets.UTF_8);
        // No reads queued → session.read() returns null
        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(req), List.of());
        GitProtocolSessionMachine runner = new GitProtocolSessionMachine(transport);
        StringCatMachine machine = new StringCatMachine(req, 1);

        // Machine expects 1 read but gets null → endOfInput() → machine stops with empty result
        String result = runner.run(REMOTE, OPTIONS, machine);

        assertThat(machine.endOfInputCalled).isTrue();
        assertThat(result).isEmpty();
    }
}
