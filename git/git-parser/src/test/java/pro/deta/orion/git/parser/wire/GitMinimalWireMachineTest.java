package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class GitMinimalWireMachineTest {

    @Test
    void defaultTestContextEnablesAllSupportedFeatures() {
        OutputFixture output = outputFixture();

        try {
            GitMinimalWireMachine.Context context =
                    GitMinimalWireMachine.testContext(
                            UnpooledByteBufAllocator.DEFAULT,
                            output.clientOutput(),
                            new InMemoryNativeGitRepositoryProvider(),
                            GitNativeRepositoryAccessHook.ALLOW_ALL);

            assertThat(context.configuration)
                    .isEqualTo(GitWireConfiguration.allSupported());
        } finally {
            output.outbound().release();
        }
    }

    @Test
    void explicitConfigurationIsStoredOnContext() {
        GitWireConfiguration configuration = disabledConfiguration();
        OutputFixture output = outputFixture();

        try {
            GitMinimalWireMachine.Context context =
                    GitMinimalWireMachine.testContext(
                            UnpooledByteBufAllocator.DEFAULT,
                            output.clientOutput(),
                            new InMemoryNativeGitRepositoryProvider(),
                            GitNativeRepositoryAccessHook.ALLOW_ALL,
                            configuration);

            assertThat(context.configuration).isSameAs(configuration);
            assertThat(context.repositoryService
                    .legacyReceivePackAdvertisement(receiveRequest())
                    .capabilities())
                    .isEmpty();
        } finally {
            output.outbound().release();
        }
    }

    @Test
    void explicitConfigurationConstructorIsAvailable() {
        OutputFixture output = outputFixture();

        try {
            GitMinimalWireMachine machine = new GitMinimalWireMachine(
                    UnpooledByteBufAllocator.DEFAULT,
                    output.clientOutput(),
                    new InMemoryNativeGitRepositoryProvider(),
                    GitNativeRepositoryAccessHook.ALLOW_ALL,
                    disabledConfiguration());

            machine.close();
        } finally {
            output.outbound().release();
        }
    }

    @Test
    void explicitConfigurationConstructorRejectsNullConfiguration() {
        OutputFixture output = outputFixture();

        try {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GitMinimalWireMachine(
                            UnpooledByteBufAllocator.DEFAULT,
                            output.clientOutput(),
                            new InMemoryNativeGitRepositoryProvider(),
                            GitNativeRepositoryAccessHook.ALLOW_ALL,
                            (GitWireConfiguration) null))
                    .withMessage("configuration");
        } finally {
            output.outbound().release();
        }
    }

    private static OutputFixture outputFixture() {
        ByteBuf outbound =
                UnpooledByteBufAllocator.DEFAULT.buffer(
                        GitNativeClientOutput.BUFFER_CAPACITY,
                        GitNativeClientOutput.BUFFER_CAPACITY);
        return new OutputFixture(
                outbound,
                new GitNativeClientOutput(new RecordingBufferedByteOutput(outbound)));
    }

    private static InitialRequestData receiveRequest() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "/demo.git",
                "localhost",
                Map.of());
    }

    private static GitWireConfiguration disabledConfiguration() {
        return new GitWireConfiguration(
                new GitWireConfiguration.LegacyUploadPack(
                        false, false, false, false, false, false),
                new GitWireConfiguration.LegacyReceivePack(
                        false, false, false, false, false),
                new GitWireConfiguration.ProtocolV2(
                        false, false, false, false));
    }

    private record OutputFixture(
            ByteBuf outbound,
            GitNativeClientOutput clientOutput) {
    }
}
