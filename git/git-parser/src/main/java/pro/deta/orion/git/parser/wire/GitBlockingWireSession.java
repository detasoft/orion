package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUri;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceivePack;
import pro.deta.orion.git.parser.wire.exchange.LegacyUploadNegotiation;
import pro.deta.orion.git.parser.wire.exchange.LegacyUploadRequest;
import pro.deta.orion.git.parser.wire.exchange.LsRefsRequest;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.error.GitWireError;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GitBlockingWireSession {
    public static final int DEFAULT_INPUT_BUFFER_SIZE = 16 * 1024;

    private static final String REF_PREFIX = "ref-prefix ";
    private static final int MAX_REF_PREFIX_COUNT = 256;
    private static final int MAX_REF_PREFIX_CHARS = 65_536;
    private static final String NULL_ID = "0".repeat(40);

    private final GitBlockingWireTransport wire;
    private final GitNativeRepositoryService repositoryService;
    private final GitWireConfiguration configuration;

    public GitBlockingWireSession(
            NativeGitRepositoryProvider repositoryProvider,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration,
            NativePackfileUriSourceFactory packfileUriSourceFactory,
            GitBlockingWireTransport wire) {
        this.wire = Objects.requireNonNull(wire, "wire");
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration");
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
            wire.sendV2UploadPackAdvertisement(
                    configuration.protocolV2());
            return;
        }
        if (data.getService() == InitialRequestService.UPLOAD_PACK) {
            wire.sendAdvertisement(
                    repositoryService.legacyUploadPackAdvertisement(data));
            return;
        }
        wire.sendAdvertisement(
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
        if (version == InitialRequestData.ProtocolVersion.V2) {
            if (data.getService() != InitialRequestService.UPLOAD_PACK
                    || !configuration.protocolV2().fetch()
                    && !configuration.protocolV2().lsRefs()) {
                throw new IOException(
                        "Blocking parser only supports protocol v2 upload-pack");
            }
            readV2UploadCommands(data);
            return;
        }
        if (data.getService() == InitialRequestService.UPLOAD_PACK) {
            serveLegacyUpload(data);
            return;
        }
        serveLegacyReceive(data);
    }

    private void readV2UploadCommands(InitialRequestData data)
            throws IOException {
        V2Command command = null;
        while (true) {
            ControlState control;
            try {
                control = wire.readControlState();
            } catch (EOFException error) {
                if (command == null) {
                    return;
                }
                throw error;
            }
            switch (control.type()) {
                case DATA -> {
                    String payload = readAsciiPayload(control);
                    if (command == null) {
                        command = readV2CommandPayload(payload);
                    } else if (!isSupportedV2CommandCapability(payload)) {
                        throw invalidV2Request();
                    }
                }
                case DELIMITER -> {
                    if (command == null) {
                        throw invalidV2Request();
                    }
                    serveV2Command(data, command);
                    command = null;
                }
                case FLUSH -> {
                    return;
                }
                case RESPONSE_END -> throw invalidV2Request();
            }
        }
    }

    private V2Command readV2CommandPayload(String payload) throws IOException {
        if (payload.isEmpty()) {
            throw invalidV2Request();
        }
        if ("command=ls-refs".equals(payload)) {
            return V2Command.LS_REFS;
        }
        if ("command=fetch".equals(payload)) {
            return V2Command.FETCH;
        }
        throw invalidV2Request();
    }

    private boolean isSupportedV2CommandCapability(String payload) {
        return configuration.protocolV2().serverOption()
                && payload.startsWith("server-option=");
    }

    private void serveV2Command(
            InitialRequestData data,
            V2Command command) throws IOException {
        switch (command) {
            case LS_REFS -> serveLsRefs(data);
            case FETCH -> serveFetch(data);
        }
    }

    private void serveLsRefs(
            InitialRequestData data) throws IOException {
        LsRefsAccumulator request = new LsRefsAccumulator(configuration);
        while (true) {
            ControlState control = wire.readControlState();
            switch (control.type()) {
                case DATA -> request.accept(readAsciiPayload(control));
                case FLUSH -> {
                    GitLsRefsResponse response = repositoryService.lsRefs(
                            data,
                            request.complete());
                    wire.sendLsRefs(response);
                    return;
                }
                case DELIMITER, RESPONSE_END -> throw invalidV2Request();
            }
        }
    }

    private String readAsciiPayload(ControlState control) throws IOException {
        ByteBuf payload = wire.readPayload(control);
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

    private static IOException invalidLegacyUploadRequest(
            GitWireError.Kind kind) {
        return new IOException(
                kind.getMessage(),
                new GitGeneralException(kind));
    }

    private static IOException invalidLegacyReceiveRequest(
            GitWireError.Kind kind) {
        return new IOException(
                kind.getMessage(),
                new GitGeneralException(kind));
    }

    private enum V2Command {
        LS_REFS,
        FETCH
    }

    private void serveFetch(
            InitialRequestData data) throws IOException {
        FetchAccumulator fetch = new FetchAccumulator(configuration);
        while (true) {
            ControlState control = wire.readControlState();
            switch (control.type()) {
                case DATA -> fetch.accept(readAsciiPayload(control));
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
            wire.sendProtocolV2FetchAcknowledgments(
                    repositoryService.protocolV2FetchAcknowledgments(
                            data,
                            nativeRequest),
                    request.sidebandAll());
            return;
        }
        GitBlockingWireTransport.ProtocolV2PackfileResponse response = null;
        try {
            NativeFetchResponse fetch =
                    repositoryService.protocolV2Fetch(data, nativeRequest);
            response = wire.beginProtocolV2Packfile(
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

    private void serveLegacyUpload(InitialRequestData data)
            throws IOException {
        GitV1Advertisement advertisement =
                repositoryService.legacyUploadPackAdvertisement(data);
        LegacyUploadRequest request = readLegacyUploadRequest(
                data,
                advertisement);
        readLegacyUploadNegotiation(request);
    }

    private LegacyUploadRequest readLegacyUploadRequest(
            InitialRequestData data,
            GitV1Advertisement advertisement) throws IOException {
        Set<GitObjectId> wants = new LinkedHashSet<>();
        Set<String> capabilities = new LinkedHashSet<>();
        while (true) {
            ControlState control = wire.readControlState();
            switch (control.type()) {
                case DATA -> acceptLegacyUploadWant(
                        wants,
                        capabilities,
                        readAsciiPayload(control));
                case FLUSH -> {
                    if (wants.isEmpty()) {
                        throw invalidLegacyUploadRequest(
                                GitWireError.Kind.MISSING_LEGACY_UPLOAD_WANT);
                    }
                    return new LegacyUploadRequest(
                            data,
                            wants,
                            capabilities,
                            advertisement);
                }
                case DELIMITER, RESPONSE_END ->
                        throw invalidLegacyUploadRequest(
                                GitWireError.Kind
                                        .UNSUPPORTED_LEGACY_UPLOAD_CONTROL);
            }
        }
    }

    private static void acceptLegacyUploadWant(
            Set<GitObjectId> wants,
            Set<String> capabilities,
            String line) throws IOException {
        if (!line.startsWith("want ")) {
            throw invalidLegacyUploadRequest(
                    GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_COMMAND);
        }
        String arguments = line.substring("want ".length());
        String[] tokens = arguments.split(" ", -1);
        if (tokens.length == 0 || !isObjectId(tokens[0])) {
            throw invalidLegacyUploadRequest(
                    GitWireError.Kind.INVALID_LEGACY_UPLOAD_OBJECT_ID);
        }
        boolean firstWant = wants.isEmpty();
        if (!firstWant && tokens.length > 1) {
            throw invalidLegacyUploadRequest(
                    GitWireError.Kind.LATE_LEGACY_UPLOAD_CAPABILITIES);
        }
        if (firstWant) {
            for (int index = 1; index < tokens.length; index++) {
                if (tokens[index].isEmpty()) {
                    throw invalidLegacyUploadRequest(
                            GitWireError.Kind.EMPTY_LEGACY_UPLOAD_CAPABILITY);
                }
            }
        }
        wants.add(GitObjectId.of(tokens[0]));
        if (firstWant) {
            for (int index = 1; index < tokens.length; index++) {
                capabilities.add(tokens[index]);
            }
        }
    }

    private void readLegacyUploadNegotiation(
            LegacyUploadRequest request) throws IOException {
        Set<GitObjectId> haves = new LinkedHashSet<>();
        while (true) {
            ControlState control = wire.readControlState();
            switch (control.type()) {
                case DATA -> {
                    LegacyUploadNegotiation negotiation =
                            acceptLegacyUploadNegotiation(request, haves,
                                    readAsciiPayload(control));
                    if (negotiation != null) {
                        serveLegacyUploadResponse(negotiation);
                        return;
                    }
                }
                case FLUSH -> wire.sendNak();
                case DELIMITER, RESPONSE_END ->
                        throw invalidLegacyUploadRequest(
                                GitWireError.Kind
                                        .UNSUPPORTED_LEGACY_UPLOAD_CONTROL);
            }
        }
    }

    private static LegacyUploadNegotiation acceptLegacyUploadNegotiation(
            LegacyUploadRequest request,
            Set<GitObjectId> haves,
            String line) throws IOException {
        if ("done".equals(line)) {
            return new LegacyUploadNegotiation(request, haves);
        }
        if (!line.startsWith("have ")) {
            throw invalidLegacyUploadRequest(
                    GitWireError.Kind
                            .UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_COMMAND);
        }
        String objectId = line.substring("have ".length());
        if (!isObjectId(objectId)) {
            throw invalidLegacyUploadRequest(
                    GitWireError.Kind.INVALID_LEGACY_UPLOAD_HAVE_OBJECT_ID);
        }
        haves.add(GitObjectId.of(objectId));
        return null;
    }

    private void serveLegacyUploadResponse(
            LegacyUploadNegotiation negotiation) throws IOException {
        if (negotiation.negotiated(GitCapability.SIDE_BAND_64K)) {
            GitBlockingWireTransport.LegacySideBandResponse response = null;
            try {
                response = wire.beginLegacySideBand64k(
                        legacyUploadProducer(negotiation),
                        GitBlockingWireTransport.SideBandChannel.DATA);
                response.advance();
            } finally {
                if (response != null) {
                    response.close();
                }
            }
            return;
        }
        GitBlockingWireTransport.LegacyPackResponse response = null;
        try {
            response = wire.beginLegacyPack(
                    legacyUploadProducer(negotiation));
            response.advance();
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private pro.deta.orion.git.nativestorage.pack.NativePackProducer
            legacyUploadProducer(LegacyUploadNegotiation negotiation) {
        return repositoryService.legacyUploadPack(
                negotiation.request().initialRequest(),
                negotiation.nativeFetchRequest());
    }

    private void serveLegacyReceive(InitialRequestData data)
            throws IOException {
        GitV1Advertisement advertisement =
                repositoryService.legacyReceivePackAdvertisement(data);
        LegacyReceiveCommandSection section = readLegacyReceiveCommands(
                data,
                advertisement);
        LegacyReceivePack receivePack = section.requiresPack()
                ? readLegacyReceivePack(section)
                : new LegacyReceivePack(section, new LooseObjectStore());
        completeLegacyReceivePack(receivePack);
    }

    private LegacyReceiveCommandSection readLegacyReceiveCommands(
            InitialRequestData data,
            GitV1Advertisement advertisement) throws IOException {
        List<LegacyReceiveCommand> commands = new ArrayList<>();
        Set<String> capabilities = new LinkedHashSet<>();
        Set<String> refNames = new LinkedHashSet<>();
        while (true) {
            ControlState control = wire.readControlState();
            switch (control.type()) {
                case DATA -> acceptLegacyReceiveCommand(
                        commands,
                        capabilities,
                        refNames,
                        readPayloadBytes(wire, control));
                case FLUSH -> {
                    if (commands.isEmpty()) {
                        throw invalidLegacyReceiveRequest(
                                GitWireError.Kind
                                        .MISSING_LEGACY_RECEIVE_COMMAND);
                    }
                    return new LegacyReceiveCommandSection(
                            data,
                            commands,
                            capabilities,
                            advertisement);
                }
                case DELIMITER, RESPONSE_END ->
                        throw invalidLegacyReceiveRequest(
                                GitWireError.Kind
                                        .UNSUPPORTED_LEGACY_RECEIVE_CONTROL);
            }
        }
    }

    private static void acceptLegacyReceiveCommand(
            List<LegacyReceiveCommand> commands,
            Set<String> capabilities,
            Set<String> refNames,
            byte[] rawPayload) throws IOException {
        int length = rawPayload.length;
        if (length > 0 && rawPayload[length - 1] == '\n') {
            length--;
        }
        if (length == 0) {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.EMPTY_LEGACY_RECEIVE_COMMAND);
        }
        int separator = receivePayloadSeparator(rawPayload, length);
        if (!commands.isEmpty() && separator >= 0) {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.LATE_LEGACY_RECEIVE_CAPABILITIES);
        }

        int commandLength = separator >= 0 ? separator : length;
        String legacyCommand = new String(
                rawPayload,
                0,
                commandLength,
                StandardCharsets.US_ASCII);
        String[] legacyTokens = legacyCommand.split(" ", -1);
        if (legacyTokens.length == 3
                && (!isObjectId(legacyTokens[0])
                        || !isObjectId(legacyTokens[1]))) {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.INVALID_LEGACY_RECEIVE_OBJECT_ID);
        }
        if (commandLength <= 82
                || rawPayload[40] != ' '
                || rawPayload[81] != ' ') {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.INVALID_LEGACY_RECEIVE_COMMAND);
        }
        String oldObjectId = new String(
                rawPayload, 0, 40, StandardCharsets.US_ASCII);
        String newObjectId = new String(
                rawPayload, 41, 40, StandardCharsets.US_ASCII);
        if (!isObjectId(oldObjectId) || !isObjectId(newObjectId)) {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.INVALID_LEGACY_RECEIVE_OBJECT_ID);
        }
        if (NULL_ID.equalsIgnoreCase(oldObjectId)
                && NULL_ID.equalsIgnoreCase(newObjectId)) {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.INVALID_LEGACY_RECEIVE_COMMAND);
        }
        for (int index = 82; index < commandLength; index++) {
            int value = rawPayload[index] & 0xff;
            if (value <= 32 || value == 127) {
                throw invalidLegacyReceiveRequest(
                        GitWireError.Kind.INVALID_LEGACY_RECEIVE_COMMAND);
            }
        }
        String refName = new String(
                rawPayload,
                82,
                commandLength - 82,
                StandardCharsets.UTF_8);
        if (!refNames.add(refName)) {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.DUPLICATE_LEGACY_RECEIVE_REF);
        }

        try {
            commands.add(new LegacyReceiveCommand(
                    GitObjectId.of(oldObjectId.toLowerCase()),
                    GitObjectId.of(newObjectId.toLowerCase()),
                    refName));
        } catch (IllegalArgumentException error) {
            refNames.remove(refName);
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.INVALID_LEGACY_RECEIVE_COMMAND);
        }
        if (separator >= 0) {
            acceptLegacyReceiveCapabilities(
                    capabilities,
                    rawPayload,
                    separator,
                    length);
        }
    }

    private static int receivePayloadSeparator(
            byte[] rawPayload,
            int length) throws IOException {
        int separator = -1;
        for (int index = 0; index < length; index++) {
            int value = rawPayload[index] & 0xff;
            if (value == 0) {
                if (separator >= 0) {
                    throw invalidLegacyReceiveRequest(
                            GitWireError.Kind
                                    .INVALID_LEGACY_RECEIVE_COMMAND);
                }
                separator = index;
            } else if (value < 32 || value == 127) {
                throw invalidLegacyReceiveRequest(
                        GitWireError.Kind.INVALID_LEGACY_RECEIVE_COMMAND);
            }
        }
        return separator;
    }

    private static void acceptLegacyReceiveCapabilities(
            Set<String> capabilities,
            byte[] rawPayload,
            int separator,
            int length) throws IOException {
        String capabilityLine = new String(
                rawPayload,
                separator + 1,
                length - separator - 1,
                StandardCharsets.US_ASCII).trim();
        if (capabilityLine.isEmpty()) {
            throw invalidLegacyReceiveRequest(
                    GitWireError.Kind.EMPTY_LEGACY_RECEIVE_CAPABILITY);
        }
        String[] capabilityTokens = capabilityLine.split(" ", -1);
        for (String capability : capabilityTokens) {
            if (capability.isEmpty()) {
                throw invalidLegacyReceiveRequest(
                        GitWireError.Kind.EMPTY_LEGACY_RECEIVE_CAPABILITY);
            }
            capabilities.add(capability);
        }
    }

    private byte[] readPayloadBytes(
            GitBlockingWireTransport wire,
            ControlState control) throws IOException {
        if (control.payloadLength() == 0) {
            return new byte[0];
        }
        ByteBuf payload = wire.readPayload(control);
        try {
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            return bytes;
        } finally {
            payload.release();
        }
    }

    private LegacyReceivePack readLegacyReceivePack(
            LegacyReceiveCommandSection section) throws IOException {
        PackIngestionSession session =
                repositoryService.beginLegacyReceivePack(
                        section.initialRequest());
        try {
            while (true) {
                ByteBuf buffer = Unpooled.buffer(DEFAULT_INPUT_BUFFER_SIZE);
                try {
                    int read = wire.readRawInto(
                            buffer,
                            DEFAULT_INPUT_BUFFER_SIZE);
                    PackIngestionResult result = read == 0
                            ? session.endOfInput()
                            : session.accept(buffer);
                    switch (result) {
                        case PackIngestionResult.NeedInput ignored -> {
                        }
                        case PackIngestionResult.Complete complete -> {
                            return new LegacyReceivePack(
                                    section,
                                    complete.quarantine());
                        }
                        case PackIngestionResult.Failed failed ->
                                throw new IOException(
                                        "Failed to ingest native Git receive pack",
                                        failed.failure());
                    }
                    if (read == 0) {
                        throw new EOFException(
                                "Legacy receive-pack body ended before pack completed");
                    }
                } finally {
                    buffer.release();
                }
            }
        } finally {
            session.close();
        }
    }

    private void completeLegacyReceivePack(LegacyReceivePack receivePack)
            throws IOException {
        List<GitBlockingWireTransport.ReceiveCommandStatus> outputStatuses =
                new ArrayList<>();
        for (GitNativeRepositoryService.ReceivePackStatus status
                : repositoryService.completeLegacyReceivePack(receivePack)) {
            outputStatuses.add(
                    new GitBlockingWireTransport.ReceiveCommandStatus(
                            status.refName(),
                            status.ok(),
                            status.message()));
        }
        Set<String> requestedCapabilities =
                receivePack.commandSection().capabilities();
        if (!configuration.receivePack().reportStatus()
                || !requestedCapabilities.contains("report-status")) {
            return;
        }
        boolean sideBand64k = configuration.receivePack().sideBand64k()
                && requestedCapabilities.contains("side-band-64k");
        wire.sendLegacyReceivePackStatus(
                outputStatuses,
                sideBand64k);
    }

    private static boolean isObjectId(String value) {
        if (value.length() != 40) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!isHexadecimal(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexadecimal(int value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
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
            if (!wantRefs.add(refName)) {
                throw invalidV2FetchRequest();
            }
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
