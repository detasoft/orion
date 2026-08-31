package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        while (true) {
            ControlState control = pkt.readControlState();
            switch (control.type()) {
                case DATA -> readV2CommandPayload(pkt, control);
                case DELIMITER -> {
                    serveV2Command(data, V2Command.LS_REFS, pkt);
                    return;
                }
                case FLUSH -> {
                    return;
                }
                case RESPONSE_END -> throw invalidV2Request();
            }
        }
    }

    private void readV2CommandPayload(
            GitBufferedByteTransportAdapter pkt,
            ControlState control) throws IOException {
        if (control.payloadLength() == 0) {
            throw invalidV2Request();
        }
        String payload = readAsciiPayload(pkt, control);
        if (!"command=ls-refs".equals(payload)) {
            throw invalidV2Request();
        }
    }

    private void serveV2Command(
            InitialRequestData data,
            V2Command command,
            GitBufferedByteTransportAdapter pkt) throws IOException {
        switch (command) {
            case LS_REFS -> serveLsRefs(data, pkt);
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

    private enum V2Command {
        LS_REFS
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
