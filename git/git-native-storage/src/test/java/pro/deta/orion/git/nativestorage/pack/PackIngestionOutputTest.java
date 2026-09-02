package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackIngestionOutputTest {
    @Test
    void exposesCompletedIngestionAndOwnsSessionClose() throws Exception {
        PackIngestionResult.Complete complete =
                new PackIngestionResult.Complete(new LooseObjectStore());
        RecordingSession session = new RecordingSession(complete, complete);
        PackIngestionOutput output = new PackIngestionOutput(session);
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{1});
        try {
            output.write(input);

            assertThat(output.completed()).isTrue();
            assertThat(output.complete()).isSameAs(complete);
            assertThat(session.closed).isFalse();
        } finally {
            input.release();
            output.close();
        }
        assertThat(session.closed).isTrue();
    }

    @Test
    void reportsTypedSessionFailureAsIOException() {
        PackParseException failure = new PackParseException("broken pack");
        RecordingSession session = new RecordingSession(
                new PackIngestionResult.Failed(failure),
                new PackIngestionResult.Failed(failure));
        PackIngestionOutput output = new PackIngestionOutput(session);
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{1});
        try {
            assertThatThrownBy(() -> output.write(input))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Native pack ingestion failed: broken pack")
                    .hasCause(failure);
        } finally {
            input.release();
            output.close();
        }
    }

    @Test
    void rejectsInputAfterCompletion() throws Exception {
        PackIngestionResult.Complete complete =
                new PackIngestionResult.Complete(new LooseObjectStore());
        PackIngestionOutput output = new PackIngestionOutput(
                new RecordingSession(complete, complete));
        ByteBuf first = Unpooled.wrappedBuffer(new byte[]{1});
        ByteBuf second = Unpooled.wrappedBuffer(new byte[]{2});
        try {
            output.write(first);

            assertThatThrownBy(() -> output.write(second))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Native pack ingestion received bytes after completion");
        } finally {
            first.release();
            second.release();
            output.close();
        }
    }

    @Test
    void reportsIncompleteEndOfInputDistinctly() {
        PackIngestionResult.NeedInput needInput = new PackIngestionResult.NeedInput();
        PackIngestionOutput output = new PackIngestionOutput(
                new RecordingSession(needInput, needInput));
        try {
            assertThatThrownBy(output::complete)
                    .isInstanceOf(PackIngestionOutput.IncompleteException.class)
                    .hasMessage("Native pack ingestion is incomplete");
        } finally {
            output.close();
        }
    }

    private static final class RecordingSession implements PackIngestionSession {
        private final PackIngestionResult accepted;
        private final PackIngestionResult ended;
        private boolean closed;

        private RecordingSession(PackIngestionResult accepted, PackIngestionResult ended) {
            this.accepted = accepted;
            this.ended = ended;
        }

        @Override
        public PackIngestionResult accept(ByteBuf input) {
            input.skipBytes(input.readableBytes());
            return accepted;
        }

        @Override
        public PackIngestionResult endOfInput() {
            return ended;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
