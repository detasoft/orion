package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapabilityParser;
import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GitV1AdvertisementPhases {
    private static final int SHA1_HEX_LENGTH = 40;
    private static final String EMPTY_REPOSITORY_REF = "capabilities^{}";
    private static final String PEELED_SUFFIX = "^{}";

    private GitV1AdvertisementPhases() {
    }

    static GitMinimalWireMachine.SemanticPhase firstLine() {
        return new FirstLinePhase();
    }

    private static final class FirstLinePhase implements GitMinimalWireMachine.SemanticPhase {
        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            requireData(control, packetIndex, byteOffset, "Advertisement must start with a ref data packet");
            String line = readLine(payload, packetIndex, byteOffset);
            int nulIndex = line.indexOf('\0');
            if (nulIndex != line.lastIndexOf('\0')) {
                throw error(packetIndex, byteOffset, "First advertisement line contains multiple NUL separators");
            }
            String refLine = nulIndex >= 0 ? line.substring(0, nulIndex) : line;
            String capabilityList = nulIndex >= 0 ? line.substring(nulIndex + 1) : "";
            ParsedRef firstRef = parseRef(refLine, packetIndex, byteOffset);
            values.push(FirstAdvertisementLine.class, new FirstAdvertisementLine(firstRef, capabilityList));
            return new CapabilityPhase().accept(control, payload, packetIndex, byteOffset, values);
        }
    }

    private static final class CapabilityPhase implements GitMinimalWireMachine.SemanticPhase {
        private final GitCapabilityParser parser = new GitCapabilityParser();

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            FirstAdvertisementLine firstLine = values.pop(FirstAdvertisementLine.class);
            GitCapabilitySet capabilities;
            try {
                capabilities = parser.parseCapabilityList(firstLine.capabilityList());
            } catch (IllegalArgumentException e) {
                throw error(packetIndex, byteOffset, "Invalid advertisement capability list");
            }
            values.push(FirstAdvertisementLine.class, firstLine);
            values.push(GitCapabilitySet.class, capabilities);
            return new GitMinimalWireMachine.SemanticTransition.Next(new RefListPhase());
        }
    }

    private static final class RefListPhase implements GitMinimalWireMachine.SemanticPhase {
        private final Map<String, GitAdvertisedRef> refs = new LinkedHashMap<>();
        private GitCapabilitySet capabilities;
        private boolean initialized;
        private boolean emptyRepository;

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            initialize(values, packetIndex, byteOffset);
            return switch (control.type()) {
                case DATA -> {
                    acceptRef(readLine(payload, packetIndex, byteOffset), packetIndex, byteOffset);
                    yield new GitMinimalWireMachine.SemanticTransition.Next(this);
                }
                case FLUSH -> new GitMinimalWireMachine.SemanticTransition.Complete<>(
                        GitV1Advertisement.class,
                        new GitV1Advertisement(capabilities, new ArrayList<>(refs.values()), emptyRepository));
                case DELIMITER, RESPONSE_END ->
                        throw error(packetIndex, byteOffset, "Advertisement must end with a flush packet");
            };
        }

        private void initialize(GitWireValueStack values, long packetIndex, long byteOffset) {
            if (initialized) {
                return;
            }
            capabilities = values.pop(GitCapabilitySet.class);
            FirstAdvertisementLine firstLine = values.pop(FirstAdvertisementLine.class);
            ParsedRef firstRef = firstLine.ref();
            if (firstRef.name().equals(EMPTY_REPOSITORY_REF)) {
                if (!isNullId(firstRef.objectId())) {
                    throw error(packetIndex, byteOffset, "Empty repository sentinel must use the null object id");
                }
                emptyRepository = true;
            } else {
                addDirect(firstRef, packetIndex, byteOffset);
            }
            initialized = true;
        }

        private void acceptRef(String line, long packetIndex, long byteOffset) {
            if (emptyRepository) {
                throw error(packetIndex, byteOffset, "Empty repository sentinel must be the only advertisement row");
            }
            if (line.indexOf('\0') >= 0) {
                throw error(packetIndex, byteOffset, "Capabilities are only allowed on the first advertisement row");
            }
            ParsedRef parsed = parseRef(line, packetIndex, byteOffset);
            if (parsed.name().endsWith(PEELED_SUFFIX)) {
                String baseName = parsed.name().substring(0, parsed.name().length() - PEELED_SUFFIX.length());
                GitAdvertisedRef base = refs.get(baseName);
                if (base == null) {
                    throw error(packetIndex, byteOffset, "Peeled advertisement row has no base ref");
                }
                if (base.peeledObjectId().isPresent()) {
                    throw error(packetIndex, byteOffset, "Advertisement contains duplicate peeled ref");
                }
                refs.put(baseName, base.withPeeledObjectId(parsed.objectId()));
                return;
            }
            addDirect(parsed, packetIndex, byteOffset);
        }

        private void addDirect(ParsedRef parsed, long packetIndex, long byteOffset) {
            GitAdvertisedRef previous = refs.putIfAbsent(
                    parsed.name(),
                    GitAdvertisedRef.direct(parsed.objectId(), parsed.name()));
            if (previous != null) {
                throw error(packetIndex, byteOffset, "Advertisement contains duplicate ref");
            }
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Advertisement ended before a flush packet");
        }
    }

    private static ParsedRef parseRef(String line, long packetIndex, long byteOffset) {
        int separator = line.indexOf(' ');
        if (separator != SHA1_HEX_LENGTH || separator == line.length() - 1) {
            throw error(packetIndex, byteOffset, "Malformed advertisement ref row");
        }
        String objectId = line.substring(0, separator);
        validateObjectId(objectId, packetIndex, byteOffset);
        String refName = line.substring(separator + 1);
        validateRefName(refName, packetIndex, byteOffset);
        return new ParsedRef(objectId, refName);
    }

    private static String readLine(ByteBuf payload, long packetIndex, long byteOffset) {
        String line = GitPktLineReader.stripLineEnding(payload.toString(StandardCharsets.UTF_8));
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw error(packetIndex, byteOffset, "Advertisement row contains an embedded line ending");
        }
        return line;
    }

    private static void validateObjectId(String objectId, long packetIndex, long byteOffset) {
        if (objectId.length() != SHA1_HEX_LENGTH) {
            throw error(packetIndex, byteOffset, "Advertisement object id must contain 40 hex characters");
        }
        for (int index = 0; index < objectId.length(); index++) {
            char value = objectId.charAt(index);
            if (!isHex(value)) {
                throw error(packetIndex, byteOffset, "Advertisement object id contains a non-hex character");
            }
        }
    }

    private static void validateRefName(String refName, long packetIndex, long byteOffset) {
        if (refName.isBlank()) {
            throw error(packetIndex, byteOffset, "Advertisement ref name must not be empty");
        }
        for (int index = 0; index < refName.length(); index++) {
            if (Character.isWhitespace(refName.charAt(index))) {
                throw error(packetIndex, byteOffset, "Advertisement ref name must not contain whitespace");
            }
        }
    }

    private static void requireData(
            ControlState.ControlSuccess control,
            long packetIndex,
            long byteOffset,
            String message) {
        if (control.type() != ControlState.ControlType.DATA) {
            throw error(packetIndex, byteOffset, message);
        }
    }

    private static boolean isNullId(String objectId) {
        for (int index = 0; index < objectId.length(); index++) {
            if (objectId.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static GitWireException error(long packetIndex, long byteOffset, String message) {
        return GitWireException.of(
                GitWireError.Kind.INVALID_ADVERTISEMENT,
                GitWireError.Phase.ADVERTISEMENT,
                packetIndex,
                byteOffset,
                message);
    }

    private record ParsedRef(String objectId, String name) {
    }

    private record FirstAdvertisementLine(ParsedRef ref, String capabilityList) {
    }
}
