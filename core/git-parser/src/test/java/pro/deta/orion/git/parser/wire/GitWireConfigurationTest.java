package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWireConfigurationTest {

    @Test
    void allSupportedEnablesEveryCurrentFeature() {
        GitWireConfiguration configuration =
                GitWireConfiguration.allSupported();

        assertThat(configuration.uploadPack().multiAckDetailed()).isTrue();
        assertThat(configuration.uploadPack().thinPack()).isTrue();
        assertThat(configuration.uploadPack().sideBand64k()).isTrue();
        assertThat(configuration.uploadPack().ofsDelta()).isTrue();
        assertThat(configuration.uploadPack().symref()).isTrue();
        assertThat(configuration.uploadPack().agent()).isTrue();
        assertThat(configuration.receivePack().reportStatus()).isTrue();
        assertThat(configuration.receivePack().sideBand64k()).isTrue();
        assertThat(configuration.receivePack().ofsDelta()).isTrue();
        assertThat(configuration.receivePack().objectFormat()).isTrue();
        assertThat(configuration.receivePack().agent()).isTrue();
        assertThat(configuration.protocolV2().lsRefs()).isTrue();
        assertThat(configuration.protocolV2().lsRefsUnborn()).isTrue();
        assertThat(configuration.protocolV2().fetch()).isTrue();
        assertThat(configuration.protocolV2().shallow()).isTrue();
        assertThat(configuration.protocolV2().waitForDone()).isTrue();
        assertThat(configuration.protocolV2().serverOption()).isTrue();
        assertThat(configuration.protocolV2().filter()).isTrue();
        assertThat(configuration.protocolV2().refInWant()).isTrue();
        assertThat(configuration.protocolV2().sidebandAll()).isTrue();
    }

    @Test
    void rejectsUnbornWithoutLsRefs() {
        assertThatThrownBy(() -> new GitWireConfiguration.ProtocolV2(
                false,
                true,
                true,
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lsRefsUnborn requires lsRefs");
    }

    @Test
    void rejectsWaitForDoneWithoutFetch() {
        assertThatThrownBy(() -> new GitWireConfiguration.ProtocolV2(
                true,
                true,
                false,
                true,
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("waitForDone requires fetch");
    }

    @Test
    void rejectsShallowWithoutFetch() {
        assertThatThrownBy(() -> new GitWireConfiguration.ProtocolV2(
                true,
                true,
                false,
                true,
                false,
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("shallow requires fetch");
    }

    @Test
    void rejectsFilterWithoutFetch() {
        assertThatThrownBy(() -> new GitWireConfiguration.ProtocolV2(
                true,
                true,
                false,
                false,
                false,
                true,
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("filter requires fetch");
    }

    @Test
    void rejectsRefInWantWithoutFetch() {
        assertThatThrownBy(() -> new GitWireConfiguration.ProtocolV2(
                true,
                true,
                false,
                false,
                false,
                true,
                false,
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refInWant requires fetch");
    }

    @Test
    void rejectsSidebandAllWithoutFetch() {
        assertThatThrownBy(() -> new GitWireConfiguration.ProtocolV2(
                true,
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sidebandAll requires fetch");
    }

    @Test
    void rejectsNullTopLevelSections() {
        GitWireConfiguration.LegacyUploadPack uploadPack =
                new GitWireConfiguration.LegacyUploadPack(
                        true, true, true, true, true, true);
        GitWireConfiguration.LegacyReceivePack receivePack =
                new GitWireConfiguration.LegacyReceivePack(
                        true, true, true, true, true);
        GitWireConfiguration.ProtocolV2 protocolV2 =
                new GitWireConfiguration.ProtocolV2(
                        true, true, true, true, true);

        assertThatNullPointerException()
                .isThrownBy(() -> new GitWireConfiguration(
                        null, receivePack, protocolV2))
                .withMessage("uploadPack");
        assertThatNullPointerException()
                .isThrownBy(() -> new GitWireConfiguration(
                        uploadPack, null, protocolV2))
                .withMessage("receivePack");
        assertThatNullPointerException()
                .isThrownBy(() -> new GitWireConfiguration(
                        uploadPack, receivePack, null))
                .withMessage("protocolV2");
    }
}
