package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record GitObjectLinks(GitObjectType type, List<ObjectId> targets) {
    public GitObjectLinks {
        targets = List.copyOf(targets);
    }

    public static GitObjectLinks read(GitObjectType type, long size, Optional<ObjectId> baseId,
                                      BufferedByteInputV2 input) throws IOException {
        return new GitObjectLinks(type, readTargets(type, size, input));
    }

    private static List<ObjectId> readTargets(GitObjectType type, long size, BufferedByteInputV2 input)
            throws IOException {
        if (type == GitObjectType.BLOB) {
            return List.of();
        }
        if (type == GitObjectType.TREE) {
            return readTree(size, input);
        }
        if (type != GitObjectType.COMMIT && type != GitObjectType.TAG) {
            throw new IOException("Fetch graph object was not resolved");
        }
        List<ObjectId> targets = new ArrayList<>();
        StringBuilder prefix = new StringBuilder(47);
        long lineLength = 0;
        boolean first = true;
        for (long remaining = size; remaining > 0; remaining--) {
            int next = input.readUnsignedByte();
            if (next != '\n') {
                if (prefix.length() < 47) {
                    prefix.append((char) next);
                }
                lineLength++;
                continue;
            }
            if (lineLength == 0) {
                if (first) {
                    throw new IOException("Missing Git object header");
                }
                return targets;
            }
            String line = prefix.toString();
            String field = first ? (type == GitObjectType.COMMIT ? "tree " : "object ")
                    : type == GitObjectType.COMMIT && line.startsWith("parent ") ? "parent " : null;
            if (field != null) {
                if (!line.startsWith(field) || lineLength != field.length() + 40) {
                    throw new IOException("Invalid Git object graph header");
                }
                try {
                    targets.add(new ObjectId(line.substring(field.length())));
                } catch (IllegalArgumentException error) {
                    throw new IOException("Invalid Git object graph ID", error);
                }
            }
            first = false;
            prefix.setLength(0);
            lineLength = 0;
        }
        throw new IOException("Missing Git object header terminator");
    }

    private static List<ObjectId> readTree(long remaining, BufferedByteInputV2 input) throws IOException {
        List<ObjectId> targets = new ArrayList<>();
        while (remaining > 0) {
            int mode = 0;
            int digits = 0;
            int next;
            do {
                if (remaining-- == 0) {
                    throw new IOException("Truncated tree mode");
                }
                next = input.readUnsignedByte();
                if (next != ' ') {
                    if (next < '0' || next > '7' || ++digits > 6) {
                        throw new IOException("Invalid tree mode");
                    }
                    mode = (mode << 3) | (next - '0');
                }
            } while (next != ' ');
            if (mode != FileMode.TREE.code() && mode != FileMode.REGULAR_FILE.code()
                    && mode != FileMode.EXECUTABLE_FILE.code() && mode != FileMode.SYMLINK.code()
                    && mode != FileMode.GITLINK.code()) {
                throw new IOException("Unsupported tree mode");
            }
            long nameLength = 0;
            do {
                if (remaining-- == 0) {
                    throw new IOException("Truncated tree name");
                }
                next = input.readUnsignedByte();
                if (next != 0) {
                    nameLength++;
                }
            } while (next != 0);
            if (nameLength == 0 || remaining < 20) {
                throw new IOException("Invalid tree entry");
            }
            ObjectId id = new ObjectId(input.readBytes(20));
            remaining -= 20;
            if (mode != FileMode.GITLINK.code()) {
                targets.add(id);
            }
        }
        return targets;
    }
}
