package pro.deta.orion.comm.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pro.deta.orion.comm.DtlsApplication;
import pro.deta.orion.comm.common.DtlsSessionEndpoint;
import pro.deta.orion.comm.v3.OrionDTLSAsyncHandler;
import pro.deta.orion.comm.v3.netty.OrionV3DtlsChannelInboundHandler;
import pro.deta.orion.test.util.ResourceUtils;
import pro.deta.orion.util.TimeoutReader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class OrionDTLSOpenSSLIT {
    @BeforeAll
    public static void setupLogging() {
        ResourceUtils.configureDefaultLogging();
    }

    @Test
    public void nettyDtlsServer() throws Exception {
        EchoDtlsApplication application = new EchoDtlsApplication();
        OrionDTLSAsyncHandler<InetSocketAddress> orionDTLSAsyncHandler = new OrionDTLSAsyncHandler<>(application);
        OrionV3DtlsChannelInboundHandler dtlsChannelInboundHandler = new OrionV3DtlsChannelInboundHandler(orionDTLSAsyncHandler);

        NioEventLoopGroup serverGroup = new NioEventLoopGroup();
        EventExecutorGroup cryptoGroup = new DefaultEventExecutorGroup(4);
        Bootstrap serverBootstrap = new Bootstrap();
        serverBootstrap.group(serverGroup)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(cryptoGroup, "cryptoGroup", dtlsChannelInboundHandler);
                    }
                });

        int serverPort = 5555;
        ChannelFuture serverFuture = serverBootstrap.bind(serverPort).sync();
        try {
            testOpenSSLConnection("127.0.0.1", serverPort, application);
        } finally {
            serverFuture.channel().close().sync();
            cryptoGroup.shutdownGracefully();
            serverGroup.shutdownGracefully();
        }
    }

    void testOpenSSLConnection(String host, int port, EchoDtlsApplication application) throws Exception {
        String message = "From_client_message!!";

        ProcessBuilder pb = new ProcessBuilder("openssl", "s_client", "-connect", host + ":" + port, "-dtls", "-verify", "1");
        Process process = pb.start();

        try (Writer writer = new OutputStreamWriter(process.getOutputStream());
             BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String preActual = new TimeoutReader(reader).readAll();

            String actual = postProcessOpensslOutput(preActual + "<END>");
            assertOpenSSLHandshake(actual);

            writer.write(message + "\n");
            writer.flush();

            String actualForComparison = new TimeoutReader(reader).readAll().trim();
            assertThat(actualForComparison).isEqualTo(application.makeResponse(message));
        } finally {
            process.destroy();
        }
    }

    private void assertOpenSSLHandshake(String output) {
        assertThat(output)
                .contains("CONNECTED(")
                .contains("Certificate chain")
                .contains("Server certificate")
                .contains("Verification error: self-signed certificate")
                .contains("New, TLSv1.2, Cipher is ECDHE-RSA-AES256-GCM-SHA384")
                .contains("Cipher    : ECDHE-RSA-AES256-GCM-SHA384")
                .contains("Extended master secret: yes")
                .contains("<END>");
        assertThat(output).containsPattern("(?m)^\\s*0 s:CN\\s*=\\s*Test Certificate$");
        assertThat(output).containsPattern("(?m)^\\s*i:CN\\s*=\\s*Test Certificate$");
        assertThat(output).containsPattern("(?m)^subject=CN\\s*=\\s*Test Certificate$");
        assertThat(output).containsPattern("(?m)^issuer=CN\\s*=\\s*Test Certificate$");
        assertThat(output).containsPattern("(?m)^\\s*Protocol\\s+: DTLSv1\\.2$");
        assertThat(output).containsPattern("(?m)^\\s*Verify return code: 18 \\(self-signed certificate\\)$");
    }

    private String postProcessOpensslOutput(String text) {
        String output = text;
        String validCertificateReplace = "(v:NotBefore: )[^;]+(; NotAfter: ).*";
        String sessionIdReplace = "(Session-ID: )[\\w\\d]+";
        String masterKeyReplace = "(Master-Key: )[\\w\\d]+";
        String startTimeReplace = "(Start Time: )[\\d]+";
        String sessionIdCtxReplace = "(Session-ID-ctx: ).*";
        String certificateReplace = "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----";
        String sessionTicketReplace = "(TLS session ticket:)[\\s\\S]*?\n\n";

        output = output.replaceAll(sessionIdReplace, "$1<SESSION_ID>");
        output = output.replaceAll(sessionIdCtxReplace, "$1<SESSION_ID_CTX>");
        output = output.replaceAll(masterKeyReplace, "$1<MASTER_KEY>");
        output = output.replaceAll(startTimeReplace, "$1<START_TIME>");
        output = output.replaceAll(certificateReplace, "-----BEGIN CERTIFICATE-----\n<CERTIFICATE>\n-----END CERTIFICATE-----");
        output = output.replaceAll(sessionTicketReplace, "$1\n    <TLS_SESSION_TICKET>\n\n");
        output = output.replaceAll(validCertificateReplace, "$1<DATE>$2<DATE>");
        return output;
    }

    private static final class EchoDtlsApplication extends DtlsApplication<InetSocketAddress> {
        @Override
        public void read(DtlsSessionEndpoint<InetSocketAddress> source, ByteBuf data) {
            String input = data.readCharSequence(data.readableBytes(), StandardCharsets.UTF_8).toString();
            write(source, Unpooled.copiedBuffer(makeResponse(input).getBytes(StandardCharsets.UTF_8)));
        }

        private String makeResponse(String message) {
            return "Hello " + message;
        }
    }
}
