package pro.deta.orion.git.parser.v2.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static pro.deta.orion.git.parser.v2.capability.GitCapability.*;

class GitCapabilitiesTest {
    @Test
    void distinguishesAbsentNamesFlagsAndValuedOccurrences() {
        var capabilities = new GitCapabilities();
        assertFalse(capabilities.has(AGENT));
        assertEquals(Optional.empty(), capabilities.value(AGENT));
        assertEquals(List.of(), capabilities.values(AGENT));
        capabilities.add(GitCapabilityValue.value(SIDEBAND_ALL));
        capabilities.add(GitCapabilityValue.value(AGENT, "orion"));
        assertTrue(capabilities.has(SIDEBAND_ALL));
        assertEquals(Optional.empty(), capabilities.value(SIDEBAND_ALL));
        assertTrue(capabilities.has(AGENT));
        assertEquals(Optional.of("orion"), capabilities.value(AGENT));
        assertEquals(List.of("orion"), capabilities.values(AGENT));
    }

    @Test
    void preservesRepeatedValuesAndReturnsADetachedList() {
        var capabilities = new GitCapabilities();
        capabilities.add(GitCapabilityValue.value(SYMREF, "HEAD:refs/heads/main"));
        capabilities.add(GitCapabilityValue.value(AGENT, "orion"));
        capabilities.add(GitCapabilityValue.value(SYMREF));
        capabilities.add(GitCapabilityValue.value(SYMREF, "HEAD:refs/heads/main"));
        capabilities.add(GitCapabilityValue.value(SYMREF, "OTHER:refs/heads/other"));
        assertThrows(IllegalStateException.class, () -> capabilities.value(SYMREF));
        var values = capabilities.values(SYMREF);
        assertEquals(List.of("HEAD:refs/heads/main", "HEAD:refs/heads/main", "OTHER:refs/heads/other"), values);
        values.clear();
        assertEquals(3, capabilities.values(SYMREF).size());
    }

    @Test
    void rejectsDuplicateFlagsInSingleValueLookupAndReflectsListMutations() {
        var capabilities = new GitCapabilities();
        capabilities.add(GitCapabilityValue.value(AGENT));
        capabilities.add(GitCapabilityValue.value(AGENT));
        assertThrows(IllegalStateException.class, () -> capabilities.value(AGENT));
        capabilities.removeFirst();
        assertEquals(Optional.empty(), capabilities.value(AGENT));
        capabilities.set(0, new GitCapabilityValue("custom-name", Optional.of("任意 value")));
        assertFalse(capabilities.has(AGENT));
        assertEquals("任意 value", capabilities.getFirst().value().orElseThrow());
    }
}
