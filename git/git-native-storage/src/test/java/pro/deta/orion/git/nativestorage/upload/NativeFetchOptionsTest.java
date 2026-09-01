package pro.deta.orion.git.nativestorage.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeFetchOptionsTest {

    @Test
    void initialCarriesNegotiatedPackOptionsWithOtherDefaults() {
        NativeFetchOptions options = NativeFetchOptions.initial(
                true,
                true,
                true);

        assertThat(options.thinPack()).isTrue();
        assertThat(options.ofsDelta()).isTrue();
        assertThat(options.includeTag()).isTrue();
        assertThat(options.waitForDone()).isFalse();
        assertThat(options.depth()).isZero();
        assertThat(options.objectFilter()).isEqualTo(NativeObjectFilter.NONE);
        assertThat(options.packfileUriProtocols()).isEmpty();
        assertThat(options.clientShallowCommits()).isEmpty();
        assertThat(options.deepenRelative()).isFalse();
        assertThat(options.deepenSince()).isEqualTo(-1);
        assertThat(options.deepenNotRefs()).isEmpty();
    }
}
