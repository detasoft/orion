package pro.deta.orion.git.nativestorage.upload;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NativeFetchResponseTest {

    @Test
    void carriesUnshallowBoundaries() {
        GitObjectId shallow = GitObjectId.of("1".repeat(40));
        GitObjectId unshallow = GitObjectId.of("2".repeat(40));

        NativeFetchResponse response = new NativeFetchResponse(
                producer(),
                Set.of(shallow),
                Set.of(unshallow),
                Map.of(),
                List.of());

        assertThat(response.shallowBoundaries()).containsExactly(shallow);
        assertThat(response.unshallowBoundaries()).containsExactly(unshallow);
    }

    private static NativePackProducer producer() {
        return new NativePackProducer() {
            @Override
            public Result produce(ByteBuf destination) {
                return Result.COMPLETED;
            }

            @Override
            public void close() {
            }
        };
    }
}
