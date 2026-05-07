package pro.deta.orion.util.stream;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import pro.deta.orion.util.Pair;

import java.io.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.util.stream.IOTestStreamUtils.testPipeScenario;
import static pro.deta.orion.util.stream.IOTestStreamUtils.wrapIntoBufferedReader;

@Slf4j
public class PingPongStreamTest {

    // simple server responding as `"Hello " + input` messages.
    IoConsumer<ServerIO> helloBackServer = server -> {
        BufferedReader reader = wrapIntoBufferedReader(server.getReceive());
        while(true) {
            String line = reader.readLine();
            if (line == null) {
                return;
            }
            Chunk.of("Hello " + line).writeTo(server.getSend());
        }
    };

    @Test
    public void testPingPongStream() throws IOException, InterruptedException {
        IoConsumer<ClientIO> simpleTest1AssertiveClient = client -> {
            BufferedReader reader = wrapIntoBufferedReader(client.getReceive());
            Chunk.of("test1").writeTo(client.getSend());
            String line = reader.readLine();
            if (line == null)
                return;
            assertThat(line).isNotEmpty().isEqualTo("Hello test1");
            client.getSend().close();
        };

        Pair<StringBuilder, List<DirectionalByteArrayOutputStream>> testResult = testPipeScenario(simpleTest1AssertiveClient, helloBackServer);
        List<DirectionalByteArrayOutputStream> result = new RecordingStandardStreams(testResult.getFirst().toString()).getStates();
        assertThat(result).isEqualTo(testResult.getSecond());
    }

    @Test
    public void testPingPongStream2() throws IOException, InterruptedException {
        IoConsumer<ClientIO> testStateClient = new AssertiveIOClient("""
                C:test1\\0A
                S:Hello test1\\0A
                """, null);
        testPipeScenario(testStateClient, helloBackServer);
    }
}
