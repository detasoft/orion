package pro.deta.orion.util.stream;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.Pair;

import java.io.*;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a client and a server against connected pipe streams and captures the server-side transcript.
 *
 * <p>The helper is intentionally small, but the thread ownership is easy to miss: the client runs on a
 * background thread while the caller's thread executes the server. TeeIOStream sits on the server side and
 * records what the client sent and what the server wrote back.</p>
 */
@Slf4j
public class IOTestStreamUtils {
    public static Pair<StringBuilder, List<DirectionalByteArrayOutputStream>> testPipeScenario(IoConsumer<ClientIO> client, IoConsumer<ServerIO> server) throws InterruptedException {
        SoftAssertions sa = new SoftAssertions();
        try {
            return testPipeScenario(client, server, sa);
        } finally {
            sa.assertAll();
        }
    }

    public static Pair<StringBuilder, List<DirectionalByteArrayOutputStream>> testPipeScenario(IoConsumer<ClientIO> client, IoConsumer<ServerIO> server, SoftAssertions sa) throws InterruptedException {
        try {
            PipedInputStream pipedInputStream = new PipedInputStream();
            PipedOutputStream clientInputStream = new PipedOutputStream(pipedInputStream);

            PipedInputStream clientOutput = new PipedInputStream();
            PipedOutputStream clientOutputStream = new PipedOutputStream(clientOutput);
            ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
            StringBuilder sb = new StringBuilder();
            List<DirectionalByteArrayOutputStream> result;
            try (TeeIOStream pingPongStream = new TeeIOStream(pipedInputStream, clientOutputStream, errorStream, sb)) {
                ClientIO clientIo = new ClientIO(clientInputStream, clientOutput);
                ServerIO serverIO = new ServerIO(pingPongStream.getInputStream(), pingPongStream.getOutputStream(), pingPongStream.getErrorStream());
                Thread thread = new Thread(() -> {
                    try {
                        client.accept(clientIo);
                    } catch (IOException e) {
                        log.error(e.getMessage(), e);
                    }
                });

                try {
                    thread.start();
                    server.accept(serverIO);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                } finally {
                    switch(OrionUtils.JVM_MODE) {
                        case DEFAULT -> thread.join(Duration.ofSeconds(5));
                        case JVM_DEBUG -> thread.join();
                    }
                }
                sa.assertThat(thread.getState()).describedAs("Client thread left in non-TERMINATED state %s (client still running after finishing scenario)", thread).isEqualTo(Thread.State.TERMINATED);
                result = pingPongStream.getStates();
            }
            return new Pair<>(sb, result);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static BufferedReader wrapIntoBufferedReader(InputStream receive) {
        return new BufferedReader(new InputStreamReader(receive));
    }
}
