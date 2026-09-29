package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Physical location, encoded type and declared inflated size of one pack entry.
 * Offsets are relative to the pack start: offset points to the entry header, packOffset to its zlib data.
 */
public record PackEntry(long offset, long packOffset, long inflatedSize, GitObjectType type,
                        OptionalLong baseOffset, Optional<ObjectId> baseId) {
}
