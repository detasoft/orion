package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineReader;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitFetchAcknowledgments;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitFetchResponse;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitFetchSection;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitFetchShallowInfo;
import pro.deta.orion.git.parser.wire.protocolv2.response.GitFetchWantedRef;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

final class GitFetchResponsePhases {
    private static final int SHA1_HEX_LENGTH = 40;
    private static final String ACK_PREFIX = "ACK ";
    private static final String SHALLOW_PREFIX = "shallow ";
    private static final String UNSHALLOW_PREFIX = "unshallow ";

    private GitFetchResponsePhases() {
    }

    static GitMinimalWireMachine.SemanticPhase firstHeader(Consumer<String> progressConsumer) {
        return new HeaderPhase(new FetchState(), PendingValue.NONE, progressConsumer);
    }

    private static final class HeaderPhase implements GitMinimalWireMachine.SemanticPhase {
        private final FetchState state;
        private final PendingValue pendingValue;
        private final Consumer<String> progressConsumer;

        private HeaderPhase(
                FetchState state,
                PendingValue pendingValue,
                Consumer<String> progressConsumer) {
            this.state = state;
            this.pendingValue = pendingValue;
            this.progressConsumer = progressConsumer;
        }

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            consumePending(values);
            requireData(control, packetIndex, byteOffset, "Fetch response expected a section header");
            GitFetchSection section = parseSection(readLine(payload, packetIndex, byteOffset), packetIndex, byteOffset);
            state.enter(section, packetIndex, byteOffset);
            return switch (section) {
                case ACKNOWLEDGMENTS -> new GitMinimalWireMachine.SemanticTransition.Next(
                        new AcknowledgmentsPhase(state, progressConsumer));
                case SHALLOW_INFO -> new GitMinimalWireMachine.SemanticTransition.Next(
                        new ShallowInfoPhase(state, progressConsumer));
                case WANTED_REFS -> new GitMinimalWireMachine.SemanticTransition.Next(
                        new WantedRefsPhase(state, progressConsumer));
                case PACKFILE -> {
                    values.push(PackfileMarker.class, PackfileMarker.INSTANCE);
                    yield new GitMinimalWireMachine.SemanticTransition.EnterSideBand(
                            new AfterPackfilePhase(state),
                            progressConsumer);
                }
            };
        }

        private void consumePending(GitWireValueStack values) {
            switch (pendingValue) {
                case NONE -> {
                }
                case ACKNOWLEDGMENTS -> state.acknowledgments = values.pop(GitFetchAcknowledgments.class);
                case SHALLOW_INFO -> state.shallowInfo = values.pop(GitFetchShallowInfo.class);
                case WANTED_REFS -> state.wantedRefs = values.pop(WantedRefsValue.class).refs();
            }
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Fetch response ended before the next section");
        }
    }

    private static final class AcknowledgmentsPhase implements GitMinimalWireMachine.SemanticPhase {
        private final FetchState state;
        private final Consumer<String> progressConsumer;
        private final List<String> objectIds = new ArrayList<>();
        private boolean nak;
        private boolean ready;

        private AcknowledgmentsPhase(FetchState state, Consumer<String> progressConsumer) {
            this.state = state;
            this.progressConsumer = progressConsumer;
        }

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            return switch (control.type()) {
                case DATA -> {
                    acceptLine(readLine(payload, packetIndex, byteOffset), packetIndex, byteOffset);
                    yield new GitMinimalWireMachine.SemanticTransition.Next(this);
                }
                case DELIMITER -> {
                    values.push(GitFetchAcknowledgments.class, result());
                    yield new GitMinimalWireMachine.SemanticTransition.Next(
                            new HeaderPhase(state, PendingValue.ACKNOWLEDGMENTS, progressConsumer));
                }
                case FLUSH -> {
                    values.push(GitFetchAcknowledgments.class, result());
                    yield new GitMinimalWireMachine.SemanticTransition.Next(
                            new ResponseEndPhase(state, PendingValue.ACKNOWLEDGMENTS));
                }
                case RESPONSE_END ->
                        throw error(packetIndex, byteOffset, "Fetch acknowledgments ended without flush");
            };
        }

        private void acceptLine(String line, long packetIndex, long byteOffset) {
            if (line.equals("NAK")) {
                if (nak || !objectIds.isEmpty()) {
                    throw error(packetIndex, byteOffset, "Fetch response cannot mix ACK and NAK");
                }
                nak = true;
                return;
            }
            if (line.startsWith(ACK_PREFIX)) {
                if (nak) {
                    throw error(packetIndex, byteOffset, "Fetch response cannot mix ACK and NAK");
                }
                String objectId = line.substring(ACK_PREFIX.length());
                validateObjectId(objectId, packetIndex, byteOffset);
                if (objectIds.contains(objectId)) {
                    throw error(packetIndex, byteOffset, "Fetch response contains duplicate ACK");
                }
                objectIds.add(objectId);
                return;
            }
            if (line.equals("ready")) {
                if (ready) {
                    throw error(packetIndex, byteOffset, "Fetch response contains duplicate ready row");
                }
                ready = true;
                return;
            }
            throw error(packetIndex, byteOffset, "Unknown fetch acknowledgment row");
        }

        private GitFetchAcknowledgments result() {
            return new GitFetchAcknowledgments(objectIds, nak, ready);
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Fetch acknowledgments ended before terminal packet");
        }
    }

    private static final class ShallowInfoPhase implements GitMinimalWireMachine.SemanticPhase {
        private final FetchState state;
        private final Consumer<String> progressConsumer;
        private final List<String> shallow = new ArrayList<>();
        private final List<String> unshallow = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        private ShallowInfoPhase(FetchState state, Consumer<String> progressConsumer) {
            this.state = state;
            this.progressConsumer = progressConsumer;
        }

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            return switch (control.type()) {
                case DATA -> {
                    acceptLine(readLine(payload, packetIndex, byteOffset), packetIndex, byteOffset);
                    yield new GitMinimalWireMachine.SemanticTransition.Next(this);
                }
                case DELIMITER -> {
                    values.push(GitFetchShallowInfo.class, new GitFetchShallowInfo(shallow, unshallow));
                    yield new GitMinimalWireMachine.SemanticTransition.Next(
                            new HeaderPhase(state, PendingValue.SHALLOW_INFO, progressConsumer));
                }
                case FLUSH, RESPONSE_END ->
                        throw error(packetIndex, byteOffset, "Shallow-info must be followed by a packfile section");
            };
        }

        private void acceptLine(String line, long packetIndex, long byteOffset) {
            boolean isUnshallow;
            String objectId;
            if (line.startsWith(SHALLOW_PREFIX)) {
                isUnshallow = false;
                objectId = line.substring(SHALLOW_PREFIX.length());
            } else if (line.startsWith(UNSHALLOW_PREFIX)) {
                isUnshallow = true;
                objectId = line.substring(UNSHALLOW_PREFIX.length());
            } else {
                throw error(packetIndex, byteOffset, "Unknown shallow-info row");
            }
            validateObjectId(objectId, packetIndex, byteOffset);
            if (!seen.add(objectId)) {
                throw error(packetIndex, byteOffset, "Duplicate or contradictory shallow-info row");
            }
            if (isUnshallow) {
                unshallow.add(objectId);
            } else {
                shallow.add(objectId);
            }
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Shallow-info ended before delimiter");
        }
    }

    private static final class WantedRefsPhase implements GitMinimalWireMachine.SemanticPhase {
        private final FetchState state;
        private final Consumer<String> progressConsumer;
        private final List<GitFetchWantedRef> refs = new ArrayList<>();
        private final Set<String> refNames = new HashSet<>();

        private WantedRefsPhase(FetchState state, Consumer<String> progressConsumer) {
            this.state = state;
            this.progressConsumer = progressConsumer;
        }

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            return switch (control.type()) {
                case DATA -> {
                    acceptLine(readLine(payload, packetIndex, byteOffset), packetIndex, byteOffset);
                    yield new GitMinimalWireMachine.SemanticTransition.Next(this);
                }
                case DELIMITER -> {
                    values.push(WantedRefsValue.class, new WantedRefsValue(refs));
                    yield new GitMinimalWireMachine.SemanticTransition.Next(
                            new HeaderPhase(state, PendingValue.WANTED_REFS, progressConsumer));
                }
                case FLUSH, RESPONSE_END ->
                        throw error(packetIndex, byteOffset, "Wanted-refs must be followed by a packfile section");
            };
        }

        private void acceptLine(String line, long packetIndex, long byteOffset) {
            int separator = line.indexOf(' ');
            if (separator != SHA1_HEX_LENGTH || separator == line.length() - 1) {
                throw error(packetIndex, byteOffset, "Malformed wanted-refs row");
            }
            String objectId = line.substring(0, separator);
            String refName = line.substring(separator + 1);
            validateObjectId(objectId, packetIndex, byteOffset);
            validateRefName(refName, packetIndex, byteOffset);
            if (!refNames.add(refName)) {
                throw error(packetIndex, byteOffset, "Wanted-refs contains duplicate ref");
            }
            refs.add(new GitFetchWantedRef(objectId, refName));
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Wanted-refs ended before delimiter");
        }
    }

    private static final class AfterPackfilePhase implements GitMinimalWireMachine.SemanticPhase {
        private final FetchState state;

        private AfterPackfilePhase(FetchState state) {
            this.state = state;
        }

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            if (control.type() != ControlState.ControlType.RESPONSE_END) {
                throw error(packetIndex, byteOffset, "Fetch packfile must be followed by response-end");
            }
            values.pop(PackfileMarker.class);
            return complete(state, true);
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Fetch response ended before packfile response-end");
        }
    }

    private static final class ResponseEndPhase implements GitMinimalWireMachine.SemanticPhase {
        private final FetchState state;
        private final PendingValue pendingValue;

        private ResponseEndPhase(FetchState state, PendingValue pendingValue) {
            this.state = state;
            this.pendingValue = pendingValue;
        }

        @Override
        public GitMinimalWireMachine.SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                long packetIndex,
                long byteOffset,
                GitWireValueStack values) {
            if (control.type() != ControlState.ControlType.RESPONSE_END) {
                throw error(packetIndex, byteOffset, "Fetch response must end with response-end packet");
            }
            if (pendingValue != PendingValue.ACKNOWLEDGMENTS) {
                throw new IllegalStateException("Unexpected fetch response terminal stack value");
            }
            state.acknowledgments = values.pop(GitFetchAcknowledgments.class);
            return complete(state, false);
        }

        @Override
        public void close(GitWireValueStack values, long packetIndex, long byteOffset) {
            throw error(packetIndex, byteOffset, "Fetch response ended before response-end packet");
        }
    }

    private static GitMinimalWireMachine.SemanticTransition complete(FetchState state, boolean packfile) {
        return new GitMinimalWireMachine.SemanticTransition.Complete<>(
                GitFetchResponse.class,
                new GitFetchResponse(
                        Optional.ofNullable(state.acknowledgments),
                        Optional.ofNullable(state.shallowInfo),
                        state.wantedRefs,
                        state.sections,
                        packfile));
    }

    private static GitFetchSection parseSection(String header, long packetIndex, long byteOffset) {
        return switch (header) {
            case "acknowledgments" -> GitFetchSection.ACKNOWLEDGMENTS;
            case "shallow-info" -> GitFetchSection.SHALLOW_INFO;
            case "wanted-refs" -> GitFetchSection.WANTED_REFS;
            case "packfile" -> GitFetchSection.PACKFILE;
            default -> throw error(packetIndex, byteOffset, "Unknown fetch response section");
        };
    }

    private static String readLine(ByteBuf payload, long packetIndex, long byteOffset) {
        String line = GitPktLineReader.stripLineEnding(payload.toString(StandardCharsets.UTF_8));
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw error(packetIndex, byteOffset, "Fetch response row contains an embedded line ending");
        }
        return line;
    }

    private static void validateObjectId(String objectId, long packetIndex, long byteOffset) {
        if (objectId.length() != SHA1_HEX_LENGTH) {
            throw error(packetIndex, byteOffset, "Fetch response object id must contain 40 hex characters");
        }
        for (int index = 0; index < objectId.length(); index++) {
            char value = objectId.charAt(index);
            if (!isHex(value)) {
                throw error(packetIndex, byteOffset, "Fetch response object id contains a non-hex character");
            }
        }
    }

    private static void validateRefName(String refName, long packetIndex, long byteOffset) {
        if (refName.isBlank() || refName.startsWith("/") || refName.endsWith("/")
                || refName.contains("//") || refName.contains("..") || refName.endsWith(".lock")) {
            throw error(packetIndex, byteOffset, "Fetch response contains invalid ref name");
        }
        for (int index = 0; index < refName.length(); index++) {
            char value = refName.charAt(index);
            if (value < 0x20 || value == 0x7f || value == ' ' || value == '~'
                    || value == '^' || value == ':' || value == '?' || value == '*'
                    || value == '[' || value == '\\') {
                throw error(packetIndex, byteOffset, "Fetch response contains invalid ref name");
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

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static GitWireException error(long packetIndex, long byteOffset, String message) {
        return GitWireException.of(
                GitWireError.Kind.INVALID_PROTOCOL_V2_RESPONSE,
                GitWireError.Phase.FETCH_RESPONSE,
                packetIndex,
                byteOffset,
                message);
    }

    private enum PendingValue {
        NONE,
        ACKNOWLEDGMENTS,
        SHALLOW_INFO,
        WANTED_REFS
    }

    private static final class FetchState {
        private final List<GitFetchSection> sections = new ArrayList<>();
        private GitFetchAcknowledgments acknowledgments;
        private GitFetchShallowInfo shallowInfo;
        private List<GitFetchWantedRef> wantedRefs = List.of();
        private int lastSectionOrdinal = -1;

        private void enter(GitFetchSection section, long packetIndex, long byteOffset) {
            if (section.ordinal() <= lastSectionOrdinal) {
                throw error(packetIndex, byteOffset, "Duplicate or out-of-order fetch response section");
            }
            lastSectionOrdinal = section.ordinal();
            sections.add(section);
        }
    }

    private record WantedRefsValue(List<GitFetchWantedRef> refs) {
        private WantedRefsValue {
            refs = List.copyOf(refs);
        }
    }

    private enum PackfileMarker {
        INSTANCE
    }
}
