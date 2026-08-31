package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUri;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LsRefsRequest;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.error.GitWireError;
import pro.deta.orion.git.parser.wire.pkt.GitBufferedByteTransportAdapter;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GitBlockingWireSession {
    private static final String REF_PREFIX = "ref-prefix ";
    private static final int MAX_REF_PREFIX_COUNT = 256;
    private static final int MAX_REF_PREFIX_CHARS = 65_536;

    private final ByteBufAllocator allocator;
    private final BufferedByteInput input;
    private final GitNativeClientOutput clientOutput;
    private final GitNativeRepositoryService repositoryService;
    private final GitWireConfiguration configuration;

    public GitBlockingWireSession(
            ByteBufAllocator allocator,
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration,
            NativePackfileUriSourceFactory packfileUriSourceFactory,
            BufferedByteInput input,
            BufferedByteOutput output) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.input = input;
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration");
        clientOutput = new GitNativeClientOutput(
                Objects.requireNonNull(output, "output"));
        repositoryService = new GitNativeRepositoryService(
                Objects.requireNonNull(
                        repositoryProvider,
                        "repositoryProvider"),
                Objects.requireNonNull(accessHook, "accessHook"),
                configuration,
                Objects.requireNonNull(
                        packfileUriSourceFactory,
                        "packfileUriSourceFactory"));
    }

    public void advertise(InitialRequestData data) throws IOException {
        Objects.requireNonNull(data, "data");
        if (data.getService() == InitialRequestService.UPLOAD_PACK
                && data.getProtocolVersion()
                        .orElse(null)
                == InitialRequestData.ProtocolVersion.V2) {
            clientOutput.sendV2UploadPackAdvertisement(
                    configuration.protocolV2());
            return;
        }
        if (data.getService() == InitialRequestService.UPLOAD_PACK) {
            clientOutput.sendAdvertisement(
                    repositoryService.legacyUploadPackAdvertisement(data));
            return;
        }
        clientOutput.sendAdvertisement(
                repositoryService.legacyReceivePackAdvertisement(data));
    }

    public void serveCommand(InitialRequestData data) throws IOException {
        advertise(data);
        serveSmartHttpPost(data);
    }

    public void serveSmartHttpPost(InitialRequestData data)
            throws IOException {
        Objects.requireNonNull(data, "data");
        InitialRequestData.ProtocolVersion version =
                data.getProtocolVersion().orElse(null);
        if (data.getService() != InitialRequestService.UPLOAD_PACK
                || version != InitialRequestData.ProtocolVersion.V2) {
            throw new IOException(
                    "Blocking parser only supports protocol v2 ls-refs");
        }
        readV2UploadCommands(data);
    }

    private void readV2UploadCommands(InitialRequestData data)
            throws IOException {
        GitBufferedByteTransportAdapter pkt = pkt();
        V2Command command = null;
        while (true) {
            ControlState control = pkt.readControlState();
            switch (control.type()) {
                case DATA -> {
                    if (command != null) {
                        throw invalidV2Request();
                    }
                    command = readV2CommandPayload(pkt, control);
                }
                case DELIMITER -> {
                    if (command == null) {
                        throw invalidV2Request();
                    }
                    serveV2Command(data, command, pkt);
                    return;
                }
                case FLUSH -> {
                    return;
                }
                case RESPONSE_END -> throw invalidV2Request();
            }
        }
    }

    private V2Command readV2CommandPayload(
            GitBufferedByteTransportAdapter pkt,
            ControlState control) throws IOException {
        if (control.payloadLength() == 0) {
            throw invalidV2Request();
        }
        String payload = readAsciiPayload(pkt, control);
        if ("command=ls-refs".equals(payload)) {
            return V2Command.LS_REFS;
        }
        if ("command=fetch".equals(payload)) {
            return V2Command.FETCH;
        }
        if (payload.startsWith("server-option=")) {
            throw invalidV2Request();
        }
        throw invalidV2Request();
    }

    private void serveV2Command(
            InitialRequestData data,
            V2Command command,
            GitBufferedByteTransportAdapter pkt) throws IOException {
        switch (command) {
            case LS_REFS -> serveLsRefs(data, pkt);
            case FETCH -> serveFetch(data, pkt);
        }
    }

    private void serveLsRefs(
            InitialRequestData data,
            GitBufferedByteTransportAdapter pkt) throws IOException {
        LsRefsAccumulator request = new LsRefsAccumulator(configuration);
        while (true) {
            ControlState control = pkt.readControlState();
            switch (control.type()) {
                case DATA -> request.accept(readAsciiPayload(pkt, control));
                case FLUSH -> {
                    GitLsRefsResponse response = repositoryService.lsRefs(
                            data,
                            request.complete());
                    clientOutput.sendLsRefs(response);
                    return;
                }
                case DELIMITER, RESPONSE_END -> throw invalidV2Request();
            }
        }
    }

    private String readAsciiPayload(
            GitBufferedByteTransportAdapter pkt,
            ControlState control) throws IOException {
        ByteBuf payload = pkt.readPayload(control);
        try {
            return asciiLine(payload);
        } finally {
            payload.release();
        }
    }

    private static String asciiLine(ByteBuf payload) throws IOException {
        StringBuilder builder = new StringBuilder(payload.readableBytes());
        while (payload.isReadable()) {
            int value = payload.readUnsignedByte();
            boolean last = !payload.isReadable();
            if (last && value == '\n') {
                continue;
            }
            if (value < 0x20 || value > 0x7e) {
                throw invalidV2Request();
            }
            builder.append((char) value);
        }
        if (builder.isEmpty()) {
            throw invalidV2Request();
        }
        return builder.toString();
    }

    private GitBufferedByteTransportAdapter pkt() {
        if (input == null) {
            throw new IllegalStateException("input is not configured");
        }
        return new GitBufferedByteTransportAdapter(input, null, allocator);
    }

    private static IOException invalidV2Request() {
        return new IOException(
                GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST.getMessage(),
                new GitGeneralException(
                        GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST));
    }

    private static IOException invalidV2FetchRequest() {
        return new IOException(
                GitWireError.Kind.INVALID_PROTOCOL_V2_FETCH_REQUEST
                        .getMessage(),
                new GitGeneralException(
                        GitWireError.Kind.INVALID_PROTOCOL_V2_FETCH_REQUEST));
    }

    private enum V2Command {
        LS_REFS,
        FETCH
    }

    private void serveFetch(
            InitialRequestData data,
            GitBufferedByteTransportAdapter pkt) throws IOException {
        FetchAccumulator fetch = new FetchAccumulator(configuration);
        while (true) {
            ControlState control = pkt.readControlState();
            switch (control.type()) {
                case DATA -> fetch.accept(readAsciiPayload(pkt, control));
                case FLUSH -> {
                    serveFetch(data, fetch.complete());
                    return;
                }
                case DELIMITER, RESPONSE_END -> throw invalidV2FetchRequest();
            }
        }
    }

    private void serveFetch(
            InitialRequestData data,
            FetchRequest request) throws IOException {
        NativeFetchRequest nativeRequest = request.nativeRequest();
        if (!nativeRequest.done()) {
            clientOutput.sendProtocolV2FetchAcknowledgments(
                    repositoryService.protocolV2FetchAcknowledgments(
                            data,
                            nativeRequest),
                    request.sidebandAll());
            return;
        }
        GitNativeClientOutput.ProtocolV2PackfileResponse response = null;
        try {
            NativeFetchResponse fetch =
                    repositoryService.protocolV2Fetch(data, nativeRequest);
            response = clientOutput.beginProtocolV2Packfile(
                    fetch.packProducer(),
                    fetch.shallowBoundaries(),
                    fetch.wantedRefs(),
                    packfileUrisForClient(fetch, nativeRequest),
                    request.sidebandAll());
            response.advance();
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private static List<NativePackfileUri> packfileUrisForClient(
            NativeFetchResponse fetch,
            NativeFetchRequest request) {
        if (request.packfileUriProtocols().isEmpty()) {
            return List.of();
        }
        List<NativePackfileUri> allowed = new ArrayList<>();
        for (NativePackfileUri packfileUri : fetch.packfileUris()) {
            if (request.packfileUriProtocols()
                    .contains(packfileUri.protocol())) {
                allowed.add(packfileUri);
            }
        }
        return List.copyOf(allowed);
    }

    private record FetchRequest(
            NativeFetchRequest nativeRequest,
            boolean sidebandAll) {
        private FetchRequest {
            Objects.requireNonNull(nativeRequest, "nativeRequest");
        }
    }

    private static final class FetchAccumulator {
        private static final int OBJECT_ID_LENGTH = 40;

        private final GitWireConfiguration configuration;
        private final Set<GitObjectId> wants = new LinkedHashSet<>();
        private final Set<String> wantRefs = new LinkedHashSet<>();
        private final Set<String> packfileUriProtocols = new LinkedHashSet<>();
        private final Set<GitObjectId> haves = new LinkedHashSet<>();
        private boolean done;
        private boolean thinPack;
        private boolean ofsDelta;
        private boolean includeTag;
        private boolean waitForDone;
        private boolean sidebandAll;
        private int depth;
        private NativeObjectFilter objectFilter = NativeObjectFilter.NONE;
        private boolean invalid;

        private FetchAccumulator(GitWireConfiguration configuration) {
            this.configuration = Objects.requireNonNull(
                    configuration,
                    "configuration");
        }

        private void accept(String value) throws IOException {
            if (done) {
                invalid = true;
                return;
            }
            if (value.startsWith("want ")) {
                wants.add(objectId(value, "want "));
                return;
            }
            if (value.startsWith("have ")) {
                haves.add(objectId(value, "have "));
                return;
            }
            if (value.startsWith("deepen ")) {
                acceptDepth(value);
                return;
            }
            if (value.startsWith("filter ")) {
                acceptFilter(value);
                return;
            }
            if (value.startsWith("want-ref ")) {
                acceptRef(value);
                return;
            }
            if (value.startsWith("packfile-uris ")) {
                acceptPackfileUriProtocols(value);
                return;
            }
            acceptSimple(value);
        }

        private FetchRequest complete() throws IOException {
            if (invalid || (wants.isEmpty() && wantRefs.isEmpty())) {
                throw invalidV2FetchRequest();
            }
            return new FetchRequest(
                    new NativeFetchRequest(
                            wants,
                            haves,
                            done,
                            thinPack,
                            ofsDelta,
                            includeTag,
                            waitForDone,
                            depth,
                            objectFilter,
                            wantRefs,
                            packfileUriProtocols),
                    sidebandAll);
        }

        private void acceptSimple(String value) throws IOException {
            switch (value) {
                case "done" -> done = true;
                case "thin-pack" -> thinPack = true;
                case "ofs-delta" -> ofsDelta = true;
                case "include-tag" -> includeTag = true;
                case "wait-for-done" -> waitForDone = true;
                case "sideband-all" -> {
                    if (!configuration.protocolV2().sidebandAll()) {
                        invalid = true;
                    } else {
                        sidebandAll = true;
                    }
                }
                case "no-progress" -> {
                }
                default -> throw invalidV2FetchRequest();
            }
        }

        private void acceptDepth(String value) throws IOException {
            if (depth > 0 || !configuration.protocolV2().shallow()) {
                invalid = true;
                return;
            }
            String depthValue = value.substring("deepen ".length());
            if (depthValue.isEmpty()) {
                throw invalidV2FetchRequest();
            }
            long parsed = 0;
            for (int index = 0; index < depthValue.length(); index++) {
                char digit = depthValue.charAt(index);
                if (digit < '0' || digit > '9') {
                    throw invalidV2FetchRequest();
                }
                parsed = parsed * 10 + digit - '0';
                if (parsed > Integer.MAX_VALUE) {
                    throw invalidV2FetchRequest();
                }
            }
            if (parsed == 0) {
                throw invalidV2FetchRequest();
            }
            depth = (int) parsed;
        }

        private void acceptFilter(String value) throws IOException {
            if (objectFilter != NativeObjectFilter.NONE
                    || !configuration.protocolV2().filter()) {
                invalid = true;
                return;
            }
            String filter = value.substring("filter ".length());
            if ("blob:none".equals(filter)) {
                objectFilter = NativeObjectFilter.BLOB_NONE;
                return;
            }
            throw invalidV2FetchRequest();
        }

        private void acceptRef(String value) throws IOException {
            if (!configuration.protocolV2().refInWant()) {
                invalid = true;
                return;
            }
            String refName = value.substring("want-ref ".length());
            if (!isValidWantedRefName(refName)) {
                throw invalidV2FetchRequest();
            }
            wantRefs.add(refName);
        }

        private void acceptPackfileUriProtocols(String value)
                throws IOException {
            if (!configuration.protocolV2().packfileUris()
                    || !packfileUriProtocols.isEmpty()) {
                invalid = true;
                return;
            }
            String rawProtocols = value.substring(
                    "packfile-uris ".length());
            if (rawProtocols.isEmpty()) {
                throw invalidV2FetchRequest();
            }
            for (String protocol : rawProtocols.split(",", -1)) {
                if (!isValidProtocol(protocol)) {
                    throw invalidV2FetchRequest();
                }
                packfileUriProtocols.add(protocol);
            }
        }

        private static GitObjectId objectId(
                String value,
                String prefix) throws IOException {
            String objectId = value.substring(prefix.length());
            if (objectId.length() != OBJECT_ID_LENGTH) {
                throw invalidV2FetchRequest();
            }
            for (int index = 0; index < objectId.length(); index++) {
                if (!isHexadecimal(objectId.charAt(index))) {
                    throw invalidV2FetchRequest();
                }
            }
            return GitObjectId.of(objectId);
        }

        private static boolean isHexadecimal(int value) {
            return value >= '0' && value <= '9'
                    || value >= 'a' && value <= 'f'
                    || value >= 'A' && value <= 'F';
        }

        private static boolean isValidProtocol(String protocol) {
            if (protocol.isEmpty()
                    || !isAsciiLetter(protocol.charAt(0))) {
                return false;
            }
            for (int index = 1; index < protocol.length(); index++) {
                char character = protocol.charAt(index);
                if (!isAsciiLetter(character)
                        && (character < '0' || character > '9')
                        && character != '+'
                        && character != '.'
                        && character != '-') {
                    return false;
                }
            }
            return true;
        }

        private static boolean isAsciiLetter(char character) {
            return character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z';
        }

        private static boolean isValidWantedRefName(String refName) {
            return "HEAD".equals(refName) || isValidFullRefName(refName);
        }

        private static boolean isValidFullRefName(String refName) {
            if (!refName.startsWith("refs/")
                    || refName.length() == "refs/".length()
                    || refName.endsWith("/")
                    || refName.contains("//")
                    || refName.contains("..")
                    || refName.contains("@{")) {
                return false;
            }
            for (int index = 0; index < refName.length(); index++) {
                char value = refName.charAt(index);
                if (value <= 0x20
                        || value >= 0x7f
                        || value == '~'
                        || value == '^'
                        || value == ':'
                        || value == '?'
                        || value == '*'
                        || value == '['
                        || value == '\\') {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class LsRefsAccumulator {
        private final GitWireConfiguration configuration;
        private final List<String> refPrefixes = new ArrayList<>();
        private int refPrefixChars;
        private boolean peel;
        private boolean symrefs;
        private boolean unborn;

        private LsRefsAccumulator(GitWireConfiguration configuration) {
            this.configuration = Objects.requireNonNull(
                    configuration,
                    "configuration");
        }

        private void accept(String value) throws IOException {
            switch (value) {
                case "peel" -> peel = true;
                case "symrefs" -> symrefs = true;
                case "unborn" -> {
                    if (!configuration.protocolV2().lsRefsUnborn()) {
                        throw invalidV2Request();
                    }
                    unborn = true;
                }
                default -> acceptOther(value);
            }
        }

        private LsRefsRequest complete() {
            return new LsRefsRequest(
                    peel,
                    symrefs,
                    unborn,
                    refPrefixes);
        }

        private void acceptOther(String value) throws IOException {
            if (value.equals("ref-prefix")
                    || malformedKnownFlag(value)) {
                throw invalidV2Request();
            }
            if (!value.startsWith(REF_PREFIX)) {
                return;
            }
            String prefix = value.substring(REF_PREFIX.length());
            if (prefix.isEmpty()) {
                throw invalidV2Request();
            }
            int prefixChars = prefix.length();
            if (refPrefixes.size() >= MAX_REF_PREFIX_COUNT
                    || prefixChars > MAX_REF_PREFIX_CHARS - refPrefixChars) {
                throw invalidV2Request();
            }
            refPrefixes.add(prefix);
            refPrefixChars += prefixChars;
        }

        private static boolean malformedKnownFlag(String value) {
            return startsWithFlagAndExtra(value, "peel")
                    || startsWithFlagAndExtra(value, "symrefs")
                    || startsWithFlagAndExtra(value, "unborn");
        }

        private static boolean startsWithFlagAndExtra(
                String value,
                String flag) {
            return value.length() > flag.length()
                    && value.startsWith(flag)
                    && value.charAt(flag.length()) == ' ';
        }
    }
}
