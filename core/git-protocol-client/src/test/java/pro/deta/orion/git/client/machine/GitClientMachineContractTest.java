package pro.deta.orion.git.client.machine;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.client.GitProtocolService;
import pro.deta.orion.lifecycle.state.PhaseMachine;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitClientMachineContractTest {

    // Minimal test machine: Write one request -> Read one response -> Complete with the response text.
    private static final class TestMachine implements GitClientMachine<String> {
        private final PhaseMachine<TestEvent, TestPhase> phases;

        TestMachine(String request) {
            phases = new PhaseMachine<>(new Writing(request));
        }

        @Override
        public GitProtocolService service() {
            return GitProtocolService.UPLOAD_PACK;
        }

        @Override
        public GitClientAction<String> action() {
            return phases.phase().action();
        }

        @Override
        public void written() {
            phases.accept(TestEvent.WRITTEN);
        }

        @Override
        public boolean accept(ByteBuf input) {
            String text = input.toString(StandardCharsets.UTF_8);
            phases.accept(new TestEvent.Received(text));
            return true;
        }

        @Override
        public void endOfInput() {
            phases.accept(TestEvent.END_OF_INPUT);
        }

        @Override
        public void close() {
            phases.close();
        }

        sealed interface TestEvent permits TestEvent.Received, TestEvent.WrittenEvent, TestEvent.EndOfInputEvent {
            record Received(String text) implements TestEvent {}
            TestEvent WRITTEN = new WrittenEvent();
            TestEvent END_OF_INPUT = new EndOfInputEvent();
            record WrittenEvent() implements TestEvent {}
            record EndOfInputEvent() implements TestEvent {}
        }

        sealed interface TestPhase extends PhaseMachine.Phase<TestEvent, TestPhase>
                permits Writing, Reading, Done, Failed {
            GitClientAction<String> action();
        }

        record Writing(String request) implements TestPhase {
            @Override
            public GitClientAction<String> action() {
                ByteBuf buf = Unpooled.copiedBuffer(request, StandardCharsets.UTF_8);
                return new GitClientAction.Write<>(buf);
            }

            @Override
            public TestPhase accept(TestEvent event) {
                return switch (event) {
                    case TestEvent.WrittenEvent ignored -> new Reading();
                    default -> throw new IllegalStateException("Expected WRITTEN, got: " + event);
                };
            }
        }

        record Reading() implements TestPhase {
            @Override
            public GitClientAction<String> action() {
                return new GitClientAction.Read<>();
            }

            @Override
            public TestPhase accept(TestEvent event) {
                return switch (event) {
                    case TestEvent.Received r -> new Done(r.text());
                    case TestEvent.EndOfInputEvent ignored ->
                            new Failed(new GitProtocolClientException(
                                    GitProtocolClientException.Operation.ADVERTISEMENT,
                                    "Unexpected end of input"));
                    default -> throw new IllegalStateException("Expected READ input, got: " + event);
                };
            }
        }

        record Done(String result) implements TestPhase {
            @Override
            public GitClientAction<String> action() {
                return new GitClientAction.Complete<>(result);
            }

            @Override
            public boolean terminal() {
                return true;
            }

            @Override
            public TestPhase accept(TestEvent event) {
                throw new IllegalStateException("Terminal phase");
            }
        }

        record Failed(GitProtocolClientException failure) implements TestPhase {
            @Override
            public GitClientAction<String> action() {
                return new GitClientAction.Fail<>(failure);
            }

            @Override
            public boolean terminal() {
                return true;
            }

            @Override
            public TestPhase accept(TestEvent event) {
                throw new IllegalStateException("Terminal phase");
            }
        }
    }

    @Test
    void writeActionCarriesReadableBuffer() {
        TestMachine machine = new TestMachine("hello");
        GitClientAction<String> action = machine.action();

        assertThat(action).isInstanceOf(GitClientAction.Write.class);
        GitClientAction.Write<String> write = (GitClientAction.Write<String>) action;
        assertThat(write.chunk().toString(StandardCharsets.UTF_8)).isEqualTo("hello");
        write.chunk().release();
        machine.close();
    }

    @Test
    void writtenAdvancesToReadAction() {
        TestMachine machine = new TestMachine("request");

        GitClientAction<String> first = machine.action();
        assertThat(first).isInstanceOf(GitClientAction.Write.class);
        ((GitClientAction.Write<String>) first).chunk().release();

        machine.written();

        assertThat(machine.action()).isInstanceOf(GitClientAction.Read.class);
        machine.close();
    }

    @Test
    void acceptAdvancesToCompleteAction() {
        TestMachine machine = new TestMachine("req");

        ((GitClientAction.Write<String>) machine.action()).chunk().release();
        machine.written();

        ByteBuf response = Unpooled.copiedBuffer("pong", StandardCharsets.UTF_8);
        boolean release = machine.accept(response);
        assertThat(release).isTrue();
        response.release();

        GitClientAction<String> action = machine.action();
        assertThat(action).isInstanceOf(GitClientAction.Complete.class);
        assertThat(((GitClientAction.Complete<String>) action).result()).isEqualTo("pong");
        machine.close();
    }

    @Test
    void endOfInputProducesFailAction() {
        TestMachine machine = new TestMachine("req");

        ((GitClientAction.Write<String>) machine.action()).chunk().release();
        machine.written();

        machine.endOfInput();

        GitClientAction<String> action = machine.action();
        assertThat(action).isInstanceOf(GitClientAction.Fail.class);
        GitProtocolClientException failure = ((GitClientAction.Fail<String>) action).failure();
        assertThat(failure.operation())
                .isEqualTo(GitProtocolClientException.Operation.ADVERTISEMENT);
        machine.close();
    }

    @Test
    void terminalCompleteActionDoesNotAdvance() {
        TestMachine machine = new TestMachine("req");

        ((GitClientAction.Write<String>) machine.action()).chunk().release();
        machine.written();
        ByteBuf buf = Unpooled.copiedBuffer("response", StandardCharsets.UTF_8);
        machine.accept(buf);
        buf.release();

        // Already terminal
        assertThat(machine.action()).isInstanceOf(GitClientAction.Complete.class);

        assertThatThrownBy(machine::written)
                .isInstanceOf(IllegalStateException.class);
        machine.close();
    }

    @Test
    void closeIsIdempotent() {
        TestMachine machine = new TestMachine("req");

        // Consume the write chunk so no leak
        ((GitClientAction.Write<String>) machine.action()).chunk().release();
        machine.close();
        machine.close(); // must not throw
    }
}
