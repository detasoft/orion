package pro.deta.orion.git.parser.wire;

import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitByteBufTransportAdapterTest {
    private static final String MAIN_ID = "1".repeat(40);

    @Test
    void advertiseFeedsSyntheticInitialRequestIntoWireMachine() throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                providerWithMainRef();
        GitByteBufTransportAdapter adapter = adapter(provider);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        adapter.advertise(uploadV2Request(), output);

        String response = output.toString(StandardCharsets.US_ASCII);
        assertThat(response)
                .startsWith("000eversion 2\n")
                .contains("fetch=")
                .contains("packfile-uris");
    }

    @Test
    void smartHttpPostStartsAfterDiscoveryAdvertisement() throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                providerWithMainRef();
        GitByteBufTransportAdapter adapter = adapter(provider);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        adapter.serveSmartHttpPost(
                uploadV2Request(),
                new ByteArrayInputStream(lsRefsRequest()),
                output);

        String response = output.toString(StandardCharsets.US_ASCII);
        assertThat(response)
                .doesNotContain("version 2\n")
                .contains(MAIN_ID + " HEAD symref-target:refs/heads/main")
                .contains(MAIN_ID + " refs/heads/main");
    }

    @Test
    void commandTransportSupportsProtocolParametersWithoutHost()
            throws Exception {
        InMemoryNativeGitRepositoryProvider provider =
                providerWithMainRef();
        GitByteBufTransportAdapter adapter = adapter(provider);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        adapter.serveCommand(
                new InitialRequestData(
                        InitialRequestService.UPLOAD_PACK,
                        "project",
                        null,
                        Map.of("version", "2")),
                new ByteArrayInputStream(lsRefsRequest()),
                output);

        String response = output.toString(StandardCharsets.US_ASCII);
        assertThat(response)
                .startsWith("000eversion 2\n")
                .contains(MAIN_ID + " refs/heads/main");
    }

    private static GitByteBufTransportAdapter adapter(
            InMemoryNativeGitRepositoryProvider provider) {
        return new GitByteBufTransportAdapter(
                UnpooledByteBufAllocator.DEFAULT,
                provider,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                GitWireConfiguration.allSupported(),
                NativePackfileUriSourceFactory.NONE);
    }

    private static InMemoryNativeGitRepositoryProvider providerWithMainRef() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("project").valueOrFailure("repository")
                .updateRef("refs/heads/main", "0".repeat(40), MAIN_ID);
        return provider;
    }

    private static InitialRequestData uploadV2Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of("version", "2"));
    }

    private static byte[] lsRefsRequest() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePacket(output, "command=ls-refs\n");
        output.writeBytes("0001".getBytes(StandardCharsets.US_ASCII));
        writePacket(output, "symrefs\n");
        writePacket(output, "ref-prefix HEAD\n");
        writePacket(output, "ref-prefix refs/heads/\n");
        output.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static void writePacket(
            ByteArrayOutputStream output,
            String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
        output.writeBytes("%04x".formatted(payloadBytes.length + 4)
                .getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(payloadBytes);
    }
}
