package pro.deta.orion.git.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHeadsTest {
    private static final String MAIN = "refs/heads/main";
    private static final String FIRST = "1".repeat(40);
    private static final String SECOND = "2".repeat(40);

    @Test
    void preservesBranchTipsWhenTheSourceChanges() {
        Map<String, String> source = new HashMap<>(Map.of(MAIN, FIRST));
        GitHeads snapshot = new GitHeads(source);

        source.put(MAIN, SECOND);
        source.put("refs/heads/release", SECOND);

        assertThat(snapshot.heads()).containsExactlyEntriesOf(Map.of(MAIN, FIRST));
        assertThat(new GitHeads(source).heads()).containsExactlyInAnyOrderEntriesOf(Map.of(
                MAIN, SECOND, "refs/heads/release", SECOND));
    }

    @Test
    void exposesImmutableBranchTips() {
        GitHeads snapshot = new GitHeads(Map.of(MAIN, FIRST));

        assertThatThrownBy(() -> snapshot.heads().put(MAIN, SECOND))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.heads().entrySet().iterator().next().setValue(SECOND))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.heads()).containsExactlyEntriesOf(Map.of(MAIN, FIRST));
    }

    @Test
    void supportsAnEmptySnapshot() {
        GitHeads snapshot = new GitHeads(Map.of());

        assertThat(snapshot.heads()).isEmpty();
        assertThatThrownBy(() -> snapshot.heads().put(MAIN, FIRST))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"refs/tags/v1", "refs/heads/", "main"})
    void rejectsRefsOutsideTheAllBranchContract(String ref) {
        assertThatThrownBy(() -> new GitHeads(Map.of(ref, FIRST)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refs/heads/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-object-id", "gggggggggggggggggggggggggggggggggggggggg",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void rejectsMalformedObjectIds(String id) {
        assertThatThrownBy(() -> new GitHeads(Map.of(MAIN, id)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40 lowercase hexadecimal digits");
    }
}
