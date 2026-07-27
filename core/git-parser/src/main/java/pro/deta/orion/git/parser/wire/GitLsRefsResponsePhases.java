package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineReader;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitLsRef;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitLsRefAttribute;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitLsRefsResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class GitLsRefsResponsePhases {
    private static final int SHA1_HEX_LENGTH = 40;
    private static final String SYMREF_PREFIX = "symref-target:";
    private static final String PEELED_PREFIX = "peeled:";

    private GitLsRefsResponsePhases() {
    }

    static GitMinimalWireMachine.SemanticPhase rows() {
        return new RowsPhase();
    }

    private static final class RowsPhase implements GitMinimalWireMachine.SemanticPhase {
        private final List<GitLsRef> refs = new ArrayList<>();
        private final Set<String> refNames = new HashSet<>();

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            return switch (control.type()) {
                case DATA -> {
                    GitLsRef ref = parseRef(readLine(payload, packetIndex, byteOffset), packetIndex, byteOffset);
                    if (!refNames.add(ref.name())) {
                        throw error(packetIndex, byteOffset, "Ls-refs response contains duplicate ref");
                    }
                    refs.add(ref);
                    yield new GitMinimalWireMachine.SemanticTransition.Next(this);
                }
                case FLUSH -> {
                    values.push(LsRefsRows.class, new LsRefsRows(refs));
                    yield new GitMinimalWireMachine.SemanticTransition.Next(new ResponseEndPhase());
                }
                case DELIMITER, RESPONSE_END ->
                        throw error(packetIndex, byteOffset, "Ls-refs rows must end with a flush packet");
            };
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Ls-refs response ended before flush packet");
        }
    }

    private static final class ResponseEndPhase implements GitMinimalWireMachine.SemanticPhase {
        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            if (control.type() != ControlState.ControlType.RESPONSE_END) {
                throw error(packetIndex, byteOffset, "Ls-refs response must end with response-end packet");
            }
            LsRefsRows rows = values.pop(LsRefsRows.class);
            return new GitMinimalWireMachine.SemanticTransition.Complete<>(
                    GitLsRefsResponse.class,
                    new GitLsRefsResponse(rows.refs()));
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Ls-refs response ended before response-end packet");
        }
    }

    private static GitLsRef parseRef(String line, long packetIndex, long byteOffset) {
        String[] tokens = line.split(" ");
        if (tokens.length < 2 || tokens[0].isEmpty() || tokens[1].isEmpty()) {
            throw error(packetIndex, byteOffset, "Malformed ls-refs response row");
        }
        boolean unborn = tokens[0].equals("unborn");
        Optional<String> objectId;
        if (unborn) {
            objectId = Optional.empty();
        } else {
            validateObjectId(tokens[0], packetIndex, byteOffset);
            objectId = Optional.of(tokens[0]);
        }
        validateRefName(tokens[1], packetIndex, byteOffset);

        String symrefTarget = null;
        String peeledObjectId = null;
        List<GitLsRefAttribute> unknownAttributes = new ArrayList<>();
        for (int index = 2; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.isEmpty()) {
                throw error(packetIndex, byteOffset, "Ls-refs response contains an empty attribute");
            }
            if (token.startsWith(SYMREF_PREFIX)) {
                if (symrefTarget != null) {
                    throw error(packetIndex, byteOffset, "Ls-refs response contains duplicate symref-target");
                }
                symrefTarget = token.substring(SYMREF_PREFIX.length());
                validateRefName(symrefTarget, packetIndex, byteOffset);
                continue;
            }
            if (token.startsWith(PEELED_PREFIX)) {
                if (peeledObjectId != null) {
                    throw error(packetIndex, byteOffset, "Ls-refs response contains duplicate peeled attribute");
                }
                peeledObjectId = token.substring(PEELED_PREFIX.length());
                validateObjectId(peeledObjectId, packetIndex, byteOffset);
                continue;
            }
            unknownAttributes.add(parseUnknownAttribute(token, packetIndex, byteOffset));
        }
        if (unborn && peeledObjectId != null) {
            throw error(packetIndex, byteOffset, "Unborn ls-refs row cannot contain peeled attribute");
        }
        return new GitLsRef(
                objectId,
                tokens[1],
                Optional.ofNullable(symrefTarget),
                Optional.ofNullable(peeledObjectId),
                unknownAttributes,
                unborn);
    }

    private static GitLsRefAttribute parseUnknownAttribute(
            String token,
            long packetIndex,
            long byteOffset) {
        int separator = token.indexOf(':');
        String name = separator >= 0 ? token.substring(0, separator) : token;
        String value = separator >= 0 ? token.substring(separator + 1) : "";
        if (name.isEmpty()) {
            throw error(packetIndex, byteOffset, "Ls-refs attribute name must not be empty");
        }
        return new GitLsRefAttribute(name, value, token);
    }

    private static String readLine(ByteBuf payload, long packetIndex, long byteOffset) {
        String line = GitPktLineReader.stripLineEnding(payload.toString(StandardCharsets.UTF_8));
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw error(packetIndex, byteOffset, "Ls-refs row contains an embedded line ending");
        }
        return line;
    }

    private static void validateObjectId(String objectId, long packetIndex, long byteOffset) {
        if (objectId.length() != SHA1_HEX_LENGTH) {
            throw error(packetIndex, byteOffset, "Ls-refs object id must contain 40 hex characters");
        }
        for (int index = 0; index < objectId.length(); index++) {
            char value = objectId.charAt(index);
            if (!isHex(value)) {
                throw error(packetIndex, byteOffset, "Ls-refs object id contains a non-hex character");
            }
        }
    }

    private static void validateRefName(String refName, long packetIndex, long byteOffset) {
        if (refName.isBlank()) {
            throw error(packetIndex, byteOffset, "Ls-refs ref name must not be empty");
        }
        if (refName.startsWith("/") || refName.endsWith("/")
                || refName.contains("//") || refName.contains("..")
                || refName.endsWith(".lock")) {
            throw error(packetIndex, byteOffset, "Ls-refs response contains invalid ref name");
        }
        for (int index = 0; index < refName.length(); index++) {
            char value = refName.charAt(index);
            if (value < 0x20 || value == 0x7f || value == ' ' || value == '~'
                    || value == '^' || value == ':' || value == '?' || value == '*'
                    || value == '[' || value == '\\') {
                throw error(packetIndex, byteOffset, "Ls-refs response contains invalid ref name");
            }
        }
    }

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static GitWireException error(long packetIndex, long byteOffset, String message) {
        return GitWireException.of(
                GitWireError.Kind.INVALID_PROTOCOL_V2_RESPONSE,
                GitWireError.Phase.LS_REFS_RESPONSE,
                packetIndex,
                byteOffset,
                message);
    }

    private record LsRefsRows(List<GitLsRef> refs) {
        private LsRefsRows {
            refs = List.copyOf(refs);
        }
    }
}
