package pro.deta.orion.util.stream;

import org.assertj.core.api.SoftAssertions;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.Pair;

import java.io.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static pro.deta.orion.util.stream.StreamUtils.closeIt;

/**
 * Runs a client and a server against connected pipe streams and captures the server-side transcript.
 *
 * <p>The helper is intentionally small, but the thread ownership is easy to miss: the client runs on a
 * background thread while the caller's thread executes the server. RecordingStandardStreams sits on the server side and
 * records what the client sent and what the server wrote back.</p>
 */
public class IOTestStreamUtils {
    public static Pair<StringBuilder, List<DirectionalByteArrayOutputStream>> testPipeScenario(
            IoConsumer<ClientIO> client, IoConsumer<ServerIO> server) throws InterruptedException {
        SoftAssertions sa = new SoftAssertions();
        try {
            return testPipeScenario(client, server, sa);
        } finally {
            sa.assertAll();
        }
    }

    public static Pair<StringBuilder, List<DirectionalByteArrayOutputStream>> testPipeScenario(
            IoConsumer<ClientIO> client, IoConsumer<ServerIO> server, SoftAssertions sa)
            throws InterruptedException {
        try (PipedInputStream pipedInputStream = new PipedInputStream();
             PipedOutputStream clientInputStream = new PipedOutputStream(pipedInputStream);
             PipedInputStream clientOutput = new PipedInputStream();
             PipedOutputStream clientOutputStream = new PipedOutputStream(clientOutput);
             ByteArrayOutputStream errorStream = new ByteArrayOutputStream()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable closePipes = () -> {
                closeIt(clientInputStream);
                closeIt(clientOutputStream);
                closeIt(pipedInputStream);
                closeIt(clientOutput);
            };
            StringBuilder sb = new StringBuilder();
            List<DirectionalByteArrayOutputStream> result;
            try (RecordingStandardStreams pingPongStream = new RecordingStandardStreams(
                    pipedInputStream, clientOutputStream, errorStream, sb)) {
                ClientIO clientIo = new ClientIO(clientInputStream, clientOutput);
                ServerIO serverIO = new ServerIO(pingPongStream.getInputStream(),
                        pingPongStream.getOutputStream(), pingPongStream.getErrorStream());
                Thread thread = new Thread(() -> {
                    try {
                        client.accept(clientIo);
                    } catch (IOException | RuntimeException | Error cause) {
                        failure.compareAndSet(null, cause);
                        closePipes.run();
                    }
                });

                try {
                    thread.start();
                    server.accept(serverIO);
                } catch (IOException | RuntimeException | Error cause) {
                    failure.compareAndSet(null, cause);
                    closePipes.run();
                } finally {
                    switch(OrionUtils.JVM_MODE) {
                        case DEFAULT -> thread.join(Duration.ofSeconds(5));
                        case JVM_DEBUG -> thread.join();
                    }
                }
                Throwable cause = failure.get();
                if (cause instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                if (cause instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                sa.assertThat(thread.getState())
                        .describedAs("Client thread still running after finishing scenario: %s", thread)
                        .isEqualTo(Thread.State.TERMINATED);
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
