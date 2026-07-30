package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;

import static org.assertj.core.api.Assertions.assertThat;

class GitMinimalWireMachineTest {

    @Test
    void defaultTestContextEnablesAllSupportedFeatures() {
        OutputFixture output = outputFixture();

        try {
            GitMinimalWireMachine.Context context =
                    GitMinimalWireMachine.testContext(
                            UnpooledByteBufAllocator.DEFAULT,
                            output.clientOutput());

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
                            new GitNativeRepositoryService(
                                    new InMemoryNativeGitRepositoryProvider()),
                            configuration);

            assertThat(context.configuration).isSameAs(configuration);
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
                    disabledConfiguration());

            machine.close();
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
                new GitNativeClientOutput(outbound));
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
