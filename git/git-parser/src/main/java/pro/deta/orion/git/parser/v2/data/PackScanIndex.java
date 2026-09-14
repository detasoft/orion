package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.PackId;

import java.util.List;
import java.util.Objects;

/**
 * Describes a quarantined pack after physical scanning and checksum verification, before object resolution.
 * packId is the verified content checksum; packSize includes the pack header, entries, and checksum trailer.
 * Owns an immutable list of entries supplied by the scanner in physical offset order.
 * Entry count is entries.size(); each entry ends at the next entry's offset, or at the start of the checksum
 * trailer for the last entry. Those boundaries and the pack header count are validated by the scanner.
 * This value is a physical scan index, not the final ObjectId-to-offset index. Delta object IDs and confirmed
 * externalBaseIds are determined during resolution. Constructing this value does not scan or validate bytes.
 */
public record PackScanIndex(PackId packId, long packSize, List<PackEntry> entries) {
    public PackScanIndex {
        Objects.requireNonNull(packId, "packId");
        entries = List.copyOf(entries);
    }
}
