package pro.deta.orion.util.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.util.stream.StreamUtils.getByteArray;
import static pro.deta.orion.util.stream.StreamUtils.readStreamInto;

/**
 * Replays a serialized RecordingStandardStreams transcript from the client's point of view.
 *
 * <p>Client chunks are written into the outgoing stream. Server chunks are read back and compared with
 * the recorded bytes, so a saved protocol exchange can become an executable assertion.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class AssertiveIOClient implements IoConsumer<ClientIO> {
    private final List<DirectionalByteArrayOutputStream> state;
    private final SoftAssertions softAssertions;

    public AssertiveIOClient(String ioState, SoftAssertions softAssertions) {
        this(new RecordingStandardStreams(ioState).getStates(), softAssertions);
    }

    @Override
    public void accept(ClientIO client) throws IOException {
        for (DirectionalByteArrayOutputStream stream : state) {
            switch (stream.getDirection()) {
                case C -> {
                    stream.writeTo(client.getSend());
                    client.getSend().flush();
                }
                case S -> {
                    byte[] expected = stream.toByteArray();

                    ByteBuffer bu = ByteBuffer.allocate(expected.length + 128);
                    int n = readStreamInto(bu, client.getReceive());
                    if (n == -1)
                        continue;
                    int available = 0;
                    while ((available = client.getReceive().available()) > 0 || n < expected.length) {
                        n += readStreamInto(bu, client.getReceive());
                    }

                    byte[] arr = getByteArray(bu);

                    if (softAssertions != null) {
                        softAssertions.assertThat(arr).describedAs("Client expect to receive (%s) but actually got (%s)", new String(expected), new String(arr)).isEqualTo(expected);
                    }
                }
            }
        }
        client.getSend().close();
    }
}
