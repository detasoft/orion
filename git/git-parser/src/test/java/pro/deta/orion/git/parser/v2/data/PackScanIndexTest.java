package pro.deta.orion.git.parser.v2.data;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackScanIndexTest {
    @Test
    void retainsPhysicalOrderAndDependenciesWhenTheScannerReusesItsEntryList() {
        PackId packId = new PackId("1".repeat(40));
        ObjectId baseId = new ObjectId("2".repeat(40));
        PackEntry full = new PackEntry(12, 14, 100, new PackEntry.Full(ObjectType.BLOB), 0x87654321);
        PackEntry offsetDelta = new PackEntry(60, 63, 15, new PackEntry.OfsDelta(12), 0x12345678);
        PackEntry refDelta = new PackEntry(85, 107, 16, new PackEntry.RefDelta(baseId), 0x76543210);
        List<PackEntry> entries = new ArrayList<>(List.of(full, offsetDelta, refDelta));

        PackScanIndex index = new PackScanIndex(packId, 160, entries);
        entries.clear();

        assertEquals(packId, index.packId());
        assertEquals(160, index.packSize());
        assertEquals(List.of(full, offsetDelta, refDelta), index.entries());
        assertThrows(UnsupportedOperationException.class, () -> index.entries().clear());
    }

    @Test
    void rejectsMissingEntriesAndRepresentationTargets() {
        PackId packId = new PackId("1".repeat(40));
        List<PackEntry> entries = new ArrayList<>();
        entries.add(null);
        assertThrows(NullPointerException.class, () -> new PackScanIndex(packId, 32, entries));
        assertThrows(NullPointerException.class, () -> new PackEntry(12, 14, 0, null, 0));
        assertThrows(NullPointerException.class, () -> new PackEntry.Full(null));
        assertThrows(NullPointerException.class, () -> new PackEntry.RefDelta(null));
    }
}
