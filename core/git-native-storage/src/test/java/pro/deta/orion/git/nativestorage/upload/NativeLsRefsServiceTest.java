package pro.deta.orion.git.nativestorage.upload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NativeLsRefsServiceTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "a".repeat(40);
    private static final String FEATURE_ID = "b".repeat(40);
    private static final String TAG_ID = "c".repeat(40);

    private final LooseRefStore refs = new LooseRefStore();
    private final NativeLsRefsService service =
            new NativeLsRefsService(UnpooledByteBufAllocator.DEFAULT);

    @Test
    void advertisesHeadBranchesAndTagsInDeterministicOrder() {
        refs.update("refs/tags/v1.0", NULL_ID, TAG_ID);
        refs.update("refs/heads/main", NULL_ID, MAIN_ID);
        refs.update("refs/heads/feature", NULL_ID, FEATURE_ID);

        List<ByteBuf> response = service.advertise(
                refs,
                Optional.of("refs/heads/main"),
                true,
                true);

        assertThat(payloadsAndRelease(response)).containsExactly(
                MAIN_ID + " HEAD symref-target:refs/heads/main\n",
                FEATURE_ID + " refs/heads/feature\n",
                MAIN_ID + " refs/heads/main\n",
                TAG_ID + " refs/tags/v1.0\n",
                "0000");
    }

    @Test
    void advertisesUnbornHeadForEmptyRepository() {
        List<ByteBuf> response = service.advertise(
                refs,
                Optional.of("refs/heads/main"),
                true,
                true);

        assertThat(payloadsAndRelease(response)).containsExactly(
                "unborn HEAD symref-target:refs/heads/main\n",
                "0000");
    }

    private static List<String> payloadsAndRelease(List<ByteBuf> packets) {
        List<String> payloads = new ArrayList<>();
        for (ByteBuf packet : packets) {
            try {
                String encoded = packet.toString(StandardCharsets.US_ASCII);
                if ("0000".equals(encoded)) {
                    payloads.add(encoded);
                } else {
                    payloads.add(encoded.substring(4));
                }
            } finally {
                packet.release();
            }
        }
        return payloads;
    }
}
