package pro.deta.orion.git.parser.v2.capability;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitCapabilityValueTest {
    @Test
    void initializesBothComponentsAndRoundTripsCapabilityTokens() throws Exception {
        for (String token : new String[]{"thin-pack", "agent=client/1", "custom=a=b"}) {
            var value = GitCapabilityValue.parse(token);
            assertEquals(token, value.wireToken());
        }
        var value = new GitCapabilityValue("agent", Optional.of("orion"));
        assertEquals("agent", value.name());
        assertEquals(Optional.of("orion"), value.value());
    }

    @Test
    void storesNegotiationValuesButRejectsThemAsCapabilityWireTokens() {
        for (String content : new String[]{"ab".repeat(20) + " thin-pack", "refs/heads/ветка", ""}) {
            var value = new GitCapabilityValue("want", Optional.of(content));
            assertEquals(Optional.of(content), value.value());
            assertThrows(IllegalArgumentException.class, value::wireToken);
        }
    }

    @Test
    void validatesConstructorArgumentsAndMalformedWireValues() {
        for (String name : new String[]{"", "two words", "name=value"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GitCapabilityValue(name, Optional.empty()));
        }
        assertThrows(NullPointerException.class, () -> new GitCapabilityValue(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new GitCapabilityValue("agent", null));
        for (String token : new String[]{"agent=", "agent=two words", "agent=ветка", "agent=x\n"}) {
            assertThrows(IOException.class, () -> GitCapabilityValue.parse(token));
        }
        assertThrows(IllegalArgumentException.class,
                () -> GitCapabilityValue.value(GitCapability.AGENT, "two words"));
        assertThrows(IllegalArgumentException.class, () -> GitCapabilityValue.value("custom", "two words"));
    }

    @Test
    void validatesHashAlgorithmOnlyWhenRequested() throws Exception {
        assertEquals(Optional.of("sha256"), GitCapabilityValue.parse("object-format=sha256").value());
        assertEquals(Optional.of("sha256"),
                GitCapabilityValue.parse("object-format=sha256", GitHashAlgorithm.SHA256).value());
        assertThrows(IOException.class,
                () -> GitCapabilityValue.parse("object-format=sha256", GitHashAlgorithm.SHA1));
    }
}
