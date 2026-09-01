package pro.deta.orion.git.nativestorage.upload;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NativeFetchRequestTest {

    @Test
    void carriesClientShallowStateAndRelativeDepth() {
        GitObjectId want = GitObjectId.of("1".repeat(40));
        GitObjectId shallow = GitObjectId.of("2".repeat(40));

        NativeFetchRequest request = new NativeFetchRequest(
                Set.of(want),
                Set.of(),
                true,
                Set.of(),
                new NativeFetchOptions(
                        false,
                        false,
                        false,
                        false,
                        2,
                        NativeObjectFilter.NONE,
                        Set.of(),
                        Set.of(shallow),
                        true,
                        0,
                        Set.of()));

        assertThat(request.clientShallowCommits()).containsExactly(shallow);
        assertThat(request.deepenRelative()).isTrue();
        assertThat(request.shallow()).isTrue();
    }

    @Test
    void carriesTimeAndRefBasedDeepening() {
        GitObjectId want = GitObjectId.of("1".repeat(40));

        NativeFetchRequest request = new NativeFetchRequest(
                Set.of(want),
                Set.of(),
                true,
                Set.of(),
                new NativeFetchOptions(
                        false,
                        false,
                        false,
                        false,
                        0,
                        NativeObjectFilter.NONE,
                        Set.of(),
                        Set.of(),
                        false,
                        1_700_000_000L,
                        Set.of("refs/heads/main")));

        assertThat(request.deepenSince()).isEqualTo(1_700_000_000L);
        assertThat(request.deepenNotRefs()).containsExactly("refs/heads/main");
        assertThat(request.shallow()).isTrue();
    }

    @Test
    void carriesFetchOptionsAsSingleValue() {
        GitObjectId want = GitObjectId.of("1".repeat(40));
        NativeFetchOptions options = NativeFetchOptions.initial(
                true,
                true,
                true);

        NativeFetchRequest request = new NativeFetchRequest(
                Set.of(want),
                Set.of(),
                true,
                Set.of(),
                options);

        assertThat(request.options()).isSameAs(options);
        assertThat(request.thinPack()).isTrue();
        assertThat(request.ofsDelta()).isTrue();
        assertThat(request.includeTag()).isTrue();
        assertThat(request.waitForDone()).isFalse();
        assertThat(request.objectFilter())
                .isEqualTo(NativeObjectFilter.NONE);
        assertThat(request.packfileUriProtocols()).isEmpty();
    }
}
