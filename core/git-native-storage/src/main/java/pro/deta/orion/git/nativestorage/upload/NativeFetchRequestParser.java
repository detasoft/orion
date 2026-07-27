package pro.deta.orion.git.nativestorage.upload;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.parser.wire.protocolv2.GitProtocolV2Line;
import pro.deta.orion.git.parser.wire.protocolv2.GitProtocolV2Request;
import pro.deta.orion.git.parser.wire.protocolv2.GitProtocolV2SectionParser;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class NativeFetchRequestParser {
    private static final int SHA1_HEX_LENGTH = 40;
    private static final String WANT_PREFIX = "want ";
    private static final String HAVE_PREFIX = "have ";
    private static final String FILTER_PREFIX = "filter ";
    private static final String DEEPEN_PREFIX = "deepen ";

    private final int maximumArgumentCount;

    public NativeFetchRequestParser(int maximumArgumentCount) {
        if (maximumArgumentCount < 1) {
            throw new IllegalArgumentException("maximumArgumentCount must be positive");
        }
        this.maximumArgumentCount = maximumArgumentCount;
    }

    public NativeFetchRequest parse(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        return parse(GitProtocolV2SectionParser.read(input));
    }

    public NativeFetchRequest parse(GitProtocolV2Request request) {
        Objects.requireNonNull(request, "request");
        if (!"fetch".equals(request.command())) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.INVALID_REQUEST,
                    "Expected protocol v2 fetch command");
        }
        if (request.terminal() != GitProtocolV2Request.Terminal.FLUSH) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.INVALID_REQUEST,
                    "Fetch request must end with flush");
        }
        if (request.arguments().size() > maximumArgumentCount) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.INVALID_REQUEST,
                    "Fetch request contains too many arguments");
        }

        Set<GitObjectId> wants = new LinkedHashSet<>();
        Set<GitObjectId> haves = new LinkedHashSet<>();
        boolean done = false;
        boolean thinPack = false;
        boolean ofsDelta = false;

        for (GitProtocolV2Line argument : request.arguments()) {
            String line = argument.rawLine();
            if (line.startsWith(WANT_PREFIX)) {
                wants.add(GitObjectId.of(parseObjectId(line.substring(WANT_PREFIX.length()), "want")));
            } else if (line.startsWith(HAVE_PREFIX)) {
                haves.add(GitObjectId.of(parseObjectId(line.substring(HAVE_PREFIX.length()), "have")));
            } else if ("done".equals(line)) {
                done = true;
            } else if ("thin-pack".equals(line)) {
                thinPack = true;
            } else if ("ofs-delta".equals(line)) {
                ofsDelta = true;
            } else if ("no-progress".equals(line) || "include-tag".equals(line)) {
                // The no-delta MVP can safely ignore these client preferences.
            } else if (line.startsWith(FILTER_PREFIX)) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.UNSUPPORTED_FEATURE,
                        "Fetch filters are not supported");
            } else if (line.startsWith(DEEPEN_PREFIX)) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.UNSUPPORTED_FEATURE,
                        "Shallow fetch is not supported");
            } else if (!line.isBlank()) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.INVALID_REQUEST,
                        "Unsupported fetch argument: " + sanitizeArgument(line));
            }
        }

        if (wants.isEmpty()) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.INVALID_REQUEST,
                    "Fetch request must contain at least one want");
        }
        return new NativeFetchRequest(wants, haves, done, thinPack, ofsDelta);
    }

    private static String parseObjectId(String value, String role) {
        if (value.length() != SHA1_HEX_LENGTH) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.INVALID_REQUEST,
                    "Invalid " + role + " object id");
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.INVALID_REQUEST,
                        "Invalid " + role + " object id");
            }
        }
        return value.toLowerCase();
    }

    private static String sanitizeArgument(String value) {
        int firstSpace = value.indexOf(' ');
        if (firstSpace < 0) {
            return value;
        }
        return value.substring(0, firstSpace);
    }
}
