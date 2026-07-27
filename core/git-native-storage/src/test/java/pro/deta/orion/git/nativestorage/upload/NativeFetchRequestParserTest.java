package pro.deta.orion.git.nativestorage.upload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeFetchRequestParserTest {
    private static final String WANT = "1".repeat(40);
    private static final String HAVE = "2".repeat(40);

    private final GitPktLineWriter writer = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);
    private final NativeFetchRequestParser parser = new NativeFetchRequestParser(8);

    @Test
    void parsesWantHavesDoneAndPackHints() {
        ByteBuf input = request(
                data("command=fetch"),
                delimiter(),
                data("thin-pack"),
                data("ofs-delta"),
                data("no-progress"),
                data("include-tag"),
                data("want " + WANT),
                data("have " + HAVE),
                data("done"),
                flush());

        try {
            NativeFetchRequest request = parser.parse(input);

            assertThat(request.wants()).extracting(id -> id.value()).containsExactly(WANT);
            assertThat(request.haves()).extracting(id -> id.value()).containsExactly(HAVE);
            assertThat(request.done()).isTrue();
            assertThat(request.thinPack()).isTrue();
            assertThat(request.ofsDelta()).isTrue();
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsInvalidWantId() {
        ByteBuf input = request(
                data("command=fetch"),
                delimiter(),
                data("want not-a-sha1"),
                flush());

        try {
            assertThatThrownBy(() -> parser.parse(input))
                    .isInstanceOfSatisfying(GitUploadPackException.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(GitUploadPackException.Kind.INVALID_REQUEST);
                        assertThat(failure).hasMessage("Invalid want object id");
                    });
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsUnsupportedFilterBeforeServiceWork() {
        ByteBuf input = request(
                data("command=fetch"),
                delimiter(),
                data("want " + WANT),
                data("filter blob:none"),
                flush());

        try {
            assertThatThrownBy(() -> parser.parse(input))
                    .isInstanceOfSatisfying(GitUploadPackException.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(GitUploadPackException.Kind.UNSUPPORTED_FEATURE);
                        assertThat(failure).hasMessage("Fetch filters are not supported");
                    });
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsRequestsBeyondArgumentLimit() {
        NativeFetchRequestParser limited = new NativeFetchRequestParser(1);
        ByteBuf input = request(
                data("command=fetch"),
                delimiter(),
                data("want " + WANT),
                data("done"),
                flush());

        try {
            assertThatThrownBy(() -> limited.parse(input))
                    .isInstanceOfSatisfying(GitUploadPackException.class, failure ->
                            assertThat(failure.kind()).isEqualTo(GitUploadPackException.Kind.INVALID_REQUEST));
        } finally {
            input.release();
        }
    }

    private ByteBuf request(ByteBuf... packets) {
        ByteBuf input = Unpooled.buffer();
        for (ByteBuf packet : packets) {
            try {
                input.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            } finally {
                packet.release();
            }
        }
        return input;
    }

    private ByteBuf data(String line) {
        return writer.writeTextLine(line);
    }

    private ByteBuf delimiter() {
        return writer.writeDelimiter();
    }

    private ByteBuf flush() {
        return writer.writeFlush();
    }
}
