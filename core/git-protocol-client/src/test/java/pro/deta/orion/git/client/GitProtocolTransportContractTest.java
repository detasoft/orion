package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitProtocolTransportContractTest {
    @Test
    void opensBothServicesWithExactConnectionParameters() throws Exception {
        for (GitProtocolService service : GitProtocolService.values()) {
            ScriptedGitProtocolTransport transport =
                    new ScriptedGitProtocolTransport(List.of(), List.of());
            URI uri = URI.create("https://example.test/repository.git");
            GitProtocolTransportOptions options = options();

            try (GitProtocolSession ignored = transport.open(service, uri, options)) {
                assertThat(transport.openedService()).isEqualTo(service);
                assertThat(transport.openedUri()).isEqualTo(uri);
                assertThat(transport.openedOptions()).isSameAs(options);
            }

            assertThat(transport.closed()).isTrue();
        }
    }

    @Test
    void exchangesExactBinaryChunksWithoutConsumingWrittenBuffer() throws Exception {
        byte[] request = new byte[]{0, 1, (byte) 0xff};
        byte[] response = new byte[]{4, 5, 6};
        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(request), List.of(response));
        ByteBuf outbound = Unpooled.wrappedBuffer(request);
        int readerIndex = outbound.readerIndex();

        try (GitProtocolSession session = transport.open(
                GitProtocolService.UPLOAD_PACK,
                URI.create("git://example.test/repository"),
                options())) {
            session.write(outbound);
            assertThat(outbound.readerIndex()).isEqualTo(readerIndex);

            ByteBuf inbound = session.read();
            try {
                byte[] actual = new byte[inbound.readableBytes()];
                inbound.readBytes(actual);
                assertThat(actual).containsExactly(response);
            } finally {
                assertThat(inbound.release()).isTrue();
            }
        } finally {
            outbound.release();
        }
    }

    @Test
    void unexpectedWriteIsNonRetryableAndSessionStillCloses() throws Exception {
        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(new byte[]{1}), List.of());

        assertThatThrownBy(() -> {
            try (GitProtocolSession session = transport.open(
                    GitProtocolService.RECEIVE_PACK,
                    URI.create("git://example.test/repository"),
                    options())) {
                ByteBuf unexpected = Unpooled.wrappedBuffer(new byte[]{2});
                try {
                    session.write(unexpected);
                } finally {
                    unexpected.release();
                }
            }
        }).isInstanceOfSatisfying(GitProtocolTransportException.class, failure -> {
            assertThat(failure.phase()).isEqualTo(GitProtocolTransportException.Phase.WRITE);
            assertThat(failure.retryable()).isFalse();
        });
        assertThat(transport.closed()).isTrue();
    }

    @Test
    void scriptedRetryableReadFailureStillClosesSession() throws Exception {
        GitProtocolTransportException readFailure = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.READ,
                true,
                "Scripted read failure");
        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(), List.of(), readFailure);

        assertThatThrownBy(() -> {
            try (GitProtocolSession session = transport.open(
                    GitProtocolService.UPLOAD_PACK,
                    URI.create("git://example.test/repository"),
                    options())) {
                session.read();
            }
        }).isSameAs(readFailure);
        assertThat(transport.closed()).isTrue();
    }

    @Test
    void scriptedOpenAndWriteFailuresPreserveTheirPhase() throws Exception {
        GitProtocolTransportException openFailure = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.OPEN,
                true,
                "Scripted open failure");
        ScriptedGitProtocolTransport openTransport = new ScriptedGitProtocolTransport(
                List.of(),
                List.of(),
                openFailure,
                null,
                null);

        assertThatThrownBy(() -> openTransport.open(
                GitProtocolService.UPLOAD_PACK,
                URI.create("git://example.test/repository"),
                options())).isSameAs(openFailure);

        GitProtocolTransportException writeFailure = new GitProtocolTransportException(
                GitProtocolTransportException.Phase.WRITE,
                false,
                "Scripted write failure");
        ScriptedGitProtocolTransport writeTransport = new ScriptedGitProtocolTransport(
                List.of(),
                List.of(),
                null,
                writeFailure,
                null);
        assertThatThrownBy(() -> {
            try (GitProtocolSession session = writeTransport.open(
                    GitProtocolService.RECEIVE_PACK,
                    URI.create("git://example.test/repository"),
                    options())) {
                ByteBuf chunk = Unpooled.wrappedBuffer(new byte[]{1});
                try {
                    session.write(chunk);
                } finally {
                    chunk.release();
                }
            }
        }).isSameAs(writeFailure);
        assertThat(writeTransport.closed()).isTrue();
    }

    @Test
    void closeRejectsAnIncompleteScriptedExchange() throws Exception {
        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(new byte[]{1}), List.of());
        GitProtocolSession session = transport.open(
                GitProtocolService.RECEIVE_PACK,
                URI.create("git://example.test/repository"),
                options());

        assertThatThrownBy(session::close)
                .isInstanceOfSatisfying(GitProtocolTransportException.class, failure ->
                        assertThat(failure.phase())
                                .isEqualTo(GitProtocolTransportException.Phase.CLOSE));
        assertThat(transport.closed()).isTrue();
    }

    @Test
    void repeatedCloseClosesUnderlyingSessionOnce() throws Exception {
        ScriptedGitProtocolTransport transport =
                new ScriptedGitProtocolTransport(List.of(), List.of());
        GitProtocolSession session = transport.open(
                GitProtocolService.UPLOAD_PACK,
                URI.create("git://example.test/repository"),
                options());

        session.close();
        session.close();

        assertThat(transport.closeCalls()).isOne();
    }

    private static GitProtocolTransportOptions options() {
        return new GitProtocolTransportOptions(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                65_520,
                1024);
    }
}
