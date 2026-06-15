package pro.deta.orion.git.parser.wire.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RawSinkTest {
    @Test
    void rawSinkIsFinalAndStateless() {
        assertThat(Modifier.isFinal(RawSink.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(RawSink.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers())))
                .isEmpty();
    }

    @Test
    void forwardsInputToTarget() {
        RawSink rawSink = new RawSink();
        RecordingTarget target = new RecordingTarget();
        ByteBuf input = Unpooled.buffer(2);
        input.writeByte(10);
        input.writeByte(11);

        rawSink.accept(target, input);

        assertThat(target.bytes).containsExactly(10, 11);
        assertThat(input.refCnt()).isZero();
    }

    private static final class RecordingTarget implements RawSink.Target {
        private byte[] bytes;

        @Override
        public void accept(ByteBuf input) {
            try {
                bytes = new byte[input.readableBytes()];
                input.readBytes(bytes);
            } finally {
                input.release();
            }
        }
    }
}
