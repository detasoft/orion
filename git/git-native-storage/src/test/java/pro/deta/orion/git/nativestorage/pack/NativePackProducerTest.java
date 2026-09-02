package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativePackProducerTest {
    @Test
    void writesEveryChunkWithoutTakingCloseOwnership() throws Exception {
        RecordingProducer producer = new RecordingProducer();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        producer.writeTo(new OutputStreamBufferedByteOutput(bytes));

        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("onetwo");
        assertThat(producer.calls).isEqualTo(2);
        assertThat(producer.closed).isFalse();
    }

    @Test
    void rejectsAProducerThatRequestsMoreWithoutWriting() {
        NativePackProducer producer = new OverridingStalledProducer();

        assertThatThrownBy(() -> producer.writeTo(
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Native pack producer made no progress");
    }

    private static final class OverridingStalledProducer implements NativePackProducer {
        private int calls;

        @Override
        public Result produce(ByteBuf destination) {
            throw new AssertionError("Buffered-output production should be used");
        }

        @Override
        public Result produce(BufferedByteOutput destination) throws IOException {
            calls++;
            return calls == 1 ? Result.MORE : Result.COMPLETED;
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingProducer implements NativePackProducer {
        private int calls;
        private boolean closed;

        @Override
        public Result produce(ByteBuf destination) {
            calls++;
            destination.writeCharSequence(
                    calls == 1 ? "one" : "two",
                    StandardCharsets.UTF_8);
            return calls == 1 ? Result.MORE : Result.COMPLETED;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
