package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NegotiationCapabilityTest {
    @Test
    void parsesKnownNamesWithAndWithoutValues() throws Exception {
        String id = "ab".repeat(20);
        assertThat(NegotiationCapability.parse("want " + id))
                .isEqualTo(new NegotiationCapabilityValue(GitCapability.WANT, id));
        assertThat(NegotiationCapability.parse("done"))
                .isEqualTo(new NegotiationCapabilityValue(GitCapability.DONE, ""));
        assertThat(NegotiationCapability.parse("thin-pack"))
                .isEqualTo(new NegotiationCapabilityValue(GitCapability.THIN_PACK, ""));
    }

    @Test
    void preservesTheWholeValueForLegacyCapabilitiesAndUnicodeRefs() throws Exception {
        String value = "ab".repeat(20) + " multi_ack agent=client/1";
        assertThat(NegotiationCapability.parse("want " + value))
                .isEqualTo(new NegotiationCapabilityValue(GitCapability.WANT, value));
        assertThat(NegotiationCapability.parse("want-ref refs/heads/ветка"))
                .isEqualTo(new NegotiationCapabilityValue(GitCapability.WANT_REF, "refs/heads/ветка"));
    }

    @Test
    void rejectsUnknownNamesAndExplicitlyEmptyValuesWithoutTrimmingInput() {
        for (String line : new String[]{"", "future-option value", " want id", "want=id", "done ",
                "done extra", "thin-pack extra", "want", "want "}) {
            assertThatThrownBy(() -> NegotiationCapability.parse(line)).isInstanceOf(IOException.class);
        }
    }
}
