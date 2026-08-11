package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;

import java.util.LinkedHashSet;
import java.util.Set;

final class FetchPayloadContinuation implements Continuation<ByteBuf> {
    private final FetchContinuation fetch;
    private final FetchPayloadParser parser;

    FetchPayloadContinuation(
            FetchContinuation fetch,
            int payloadLength) {
        this.fetch = fetch;
        this.parser = new FetchPayloadParser(payloadLength);
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            while (parser.notDone()) {
                if (!input.isReadable()) {
                    return ContinuationFlow.await();
                }
                if (!parser.nextByte(input)) {
                    return ContinuationFlow.transition(
                            FetchContinuation.failed());
                }
            }
            FetchContinuation.FetchArgument argument =
                    parser.completeArgument();
            if (argument == null) {
                return ContinuationFlow.transition(
                        FetchContinuation.failed());
            }
            fetch.accept(argument);
            return ContinuationFlow.transition(
                    new ControlHeaderContinuation(fetch::next));
        } catch (Throwable error) {
            return ContinuationFlow.transition(
                    FetchContinuation.failed());
        }
    }

    private static final class FetchPayloadParser {
        private static final int OBJECT_ID_LENGTH = 40;

        private final StringBuilder payload = new StringBuilder();
        private int remainingBytes;

        private FetchPayloadParser(int payloadLength) {
            this.remainingBytes = payloadLength;
        }

        private boolean nextByte(ByteBuf input) {
            int value = input.readUnsignedByte();
            remainingBytes--;
            boolean last = remainingBytes == 0;
            if (last && value == '\n') {
                return !payload.isEmpty();
            }
            if (value > 0x7f || value == 0) {
                return false;
            }
            payload.append((char) value);
            return true;
        }

        private boolean notDone() {
            return remainingBytes > 0;
        }

        private FetchContinuation.FetchArgument completeArgument() {
            if (remainingBytes != 0 || payload.isEmpty()) {
                return null;
            }
            String value = payload.toString();
            if (value.startsWith("want ")) {
                return objectArgument(
                        value,
                        "want ",
                        FetchContinuation.ObjectArgumentKind.WANT);
            }
            if (value.startsWith("have ")) {
                return objectArgument(
                        value,
                        "have ",
                        FetchContinuation.ObjectArgumentKind.HAVE);
            }
            if (value.startsWith("deepen ")) {
                return depthArgument(value);
            }
            if (value.startsWith("filter ")) {
                return filterArgument(value);
            }
            if (value.startsWith("want-ref ")) {
                return refArgument(value);
            }
            if (value.startsWith("packfile-uris ")) {
                return packfileUriProtocolsArgument(value);
            }
            return switch (value) {
                case "done" -> FetchContinuation.SimpleArgument.DONE;
                case "thin-pack" ->
                        FetchContinuation.SimpleArgument.THIN_PACK;
                case "ofs-delta" ->
                        FetchContinuation.SimpleArgument.OFS_DELTA;
                case "no-progress" ->
                        FetchContinuation.SimpleArgument.NO_PROGRESS;
                case "include-tag" ->
                        FetchContinuation.SimpleArgument.INCLUDE_TAG;
                case "wait-for-done" ->
                        FetchContinuation.SimpleArgument.WAIT_FOR_DONE;
                case "sideband-all" ->
                        FetchContinuation.SimpleArgument.SIDEBAND_ALL;
                default -> null;
            };
        }

        private static FetchContinuation.FetchArgument depthArgument(
                String value) {
            String depth = value.substring("deepen ".length());
            if (depth.isEmpty()) {
                return null;
            }
            long parsed = 0;
            for (int index = 0; index < depth.length(); index++) {
                char digit = depth.charAt(index);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                parsed = parsed * 10 + digit - '0';
                if (parsed > Integer.MAX_VALUE) {
                    return null;
                }
            }
            return parsed > 0
                    ? new FetchContinuation.DepthArgument((int) parsed)
                    : null;
        }

        private static FetchContinuation.FetchArgument filterArgument(
                String value) {
            String filter = value.substring("filter ".length());
            return switch (filter) {
                case "blob:none" -> new FetchContinuation.FilterArgument(
                        NativeObjectFilter.BLOB_NONE);
                default -> null;
            };
        }

        private static FetchContinuation.FetchArgument refArgument(
                String value) {
            String refName = value.substring("want-ref ".length());
            return isValidWantedRefName(refName)
                    ? new FetchContinuation.RefArgument(refName)
                    : null;
        }

        private static FetchContinuation.FetchArgument
                packfileUriProtocolsArgument(String value) {
            String rawProtocols = value.substring("packfile-uris ".length());
            if (rawProtocols.isEmpty()) {
                return null;
            }
            Set<String> protocols = new LinkedHashSet<>();
            for (String protocol : rawProtocols.split(",", -1)) {
                if (!isValidProtocol(protocol)) {
                    return null;
                }
                protocols.add(protocol);
            }
            return new FetchContinuation.PackfileUriProtocolsArgument(
                    protocols);
        }

        private static FetchContinuation.FetchArgument objectArgument(
                String value,
                String prefix,
                FetchContinuation.ObjectArgumentKind objectKind) {
            String objectId = value.substring(prefix.length());
            if (objectId.length() != OBJECT_ID_LENGTH) {
                return null;
            }
            for (int index = 0; index < objectId.length(); index++) {
                if (!isHexadecimal(objectId.charAt(index))) {
                    return null;
                }
            }
            return new FetchContinuation.ObjectArgument(
                    objectKind,
                    GitObjectId.of(objectId));
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
}
