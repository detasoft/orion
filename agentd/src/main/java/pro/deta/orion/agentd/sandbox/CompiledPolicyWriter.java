package pro.deta.orion.agentd.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class CompiledPolicyWriter {
    public static final String FILE_NAME = "sandbox-policy.cbor";

    public byte[] encode(CompiledPolicy policy) {
        if (policy.handledRights() != LandlockRight.HANDLED_MASK) {
            throw new PolicyException("compiled policy has an invalid handled-rights mask");
        }
        List<CompiledPolicy.Rule> rules = new ArrayList<>(policy.rules());
        rules.sort(Comparator.comparing(
                rule -> rule.path().toString().getBytes(StandardCharsets.UTF_8),
                CompiledPolicyWriter::compareBytes));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        argument(output, 4, 3);
        unsigned(output, CompiledPolicy.VERSION);
        unsigned(output, policy.handledRights());
        argument(output, 4, rules.size());
        Path previous = null;
        for (CompiledPolicy.Rule rule : rules) {
            validate(rule, previous);
            argument(output, 4, 2);
            text(output, rule.path().toString());
            unsigned(output, rule.rights());
            previous = rule.path();
        }
        return output.toByteArray();
    }

    public Path write(Path sessionDirectory, CompiledPolicy policy) throws IOException {
        byte[] encoded = encode(policy);
        Path temporary = Files.createTempFile(sessionDirectory, ".sandbox-policy-", ".tmp");
        boolean moved = false;
        try {
            ownerOnly(temporary);
            Files.write(temporary, encoded);
            Path target = sessionDirectory.resolve(FILE_NAME);
            try {
                Files.move(
                        temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return target;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void validate(CompiledPolicy.Rule rule, Path previous) {
        Path path = rule.path();
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new PolicyException("compiled grant path is not absolute and normalized: " + path);
        }
        if (rule.rights() == 0 || (rule.rights() & ~LandlockRight.HANDLED_MASK) != 0) {
            throw new PolicyException("compiled grant has invalid rights: " + path);
        }
        if (previous != null && previous.equals(path)) {
            throw new PolicyException("compiled policy contains duplicate path: " + path);
        }
    }

    private static void ownerOnly(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            return;
        }
        java.io.File file = path.toFile();
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setReadable(true, true) || !file.setWritable(true, true)) {
            throw new IOException("cannot restrict compiled policy permissions");
        }
    }

    private static void text(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SourcePolicyParser.MAX_PATH_BYTES) {
            throw new PolicyException("compiled grant path is too long");
        }
        argument(output, 3, bytes.length);
        output.writeBytes(bytes);
    }

    private static void unsigned(ByteArrayOutputStream output, long value) {
        if (value < 0) {
            throw new PolicyException("compiled policy contains a negative integer");
        }
        argument(output, 0, value);
    }

    private static void argument(ByteArrayOutputStream output, int major, long value) {
        int prefix = major << 5;
        if (value < 24) {
            output.write(prefix | (int) value);
        } else if (value <= 0xff) {
            output.write(prefix | 24);
            output.write((int) value);
        } else if (value <= 0xffff) {
            output.write(prefix | 25);
            writeBigEndian(output, value, 2);
        } else if (value <= 0xffff_ffffL) {
            output.write(prefix | 26);
            writeBigEndian(output, value, 4);
        } else {
            output.write(prefix | 27);
            writeBigEndian(output, value, 8);
        }
    }

    private static void writeBigEndian(ByteArrayOutputStream output, long value, int length) {
        for (int shift = (length - 1) * 8; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xff);
        }
    }

    private static int compareBytes(byte[] left, byte[] right) {
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
