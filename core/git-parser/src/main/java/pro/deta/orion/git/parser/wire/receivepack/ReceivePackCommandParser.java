package pro.deta.orion.git.parser.wire.receivepack;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;
import pro.deta.orion.git.parser.wire.capability.GitCapabilityParser;
import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineReader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ReceivePackCommandParser {
    private static final int SHA1_HEX_LENGTH = 40;
    private static final int DEFAULT_MAX_COMMANDS = 1000;

    private final int maxCommands;
    private final GitCapabilityParser capabilityParser = new GitCapabilityParser();

    public ReceivePackCommandParser() {
        this(DEFAULT_MAX_COMMANDS);
    }

    public ReceivePackCommandParser(int maxCommands) {
        if (maxCommands < 1) {
            throw new IllegalArgumentException("maxCommands must be at least 1");
        }
        this.maxCommands = maxCommands;
    }

    public ReceivePackCommandSection read(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        int startReaderIndex = input.readerIndex();
        List<ReceivePackCommand> commands = new ArrayList<>();
        GitCapabilitySet clientCapabilities = null;
        Set<String> seenRefs = new HashSet<>();
        long packetIndex = 0;

        while (input.isReadable()) {
            GitPktLineReader.Packet packet = GitPktLineReader.read(input, packetIndex, startReaderIndex);
            packetIndex++;
            switch (packet.kind()) {
                case FLUSH -> {
                    GitCapabilitySet caps = clientCapabilities != null
                            ? clientCapabilities
                            : new GitCapabilitySet(List.of());
                    return new ReceivePackCommandSection(commands, caps);
                }
                case DATA -> {
                    String line = packet.payload();
                    boolean isFirst = clientCapabilities == null;
                    if (isFirst) {
                        int nul = line.indexOf('\0');
                        String commandLine;
                        String capabilityLine;
                        if (nul >= 0) {
                            commandLine = line.substring(0, nul);
                            capabilityLine = line.substring(nul);
                        } else {
                            commandLine = line;
                            capabilityLine = "";
                        }
                        clientCapabilities = capabilityParser.parseAdvertisementLine(commandLine + capabilityLine);
                        ReceivePackCommand command = parseCommand(commandLine, packet.packetIndex(), packet.byteOffset());
                        validateAndAdd(command, commands, seenRefs, packet.packetIndex(), packet.byteOffset());
                    } else {
                        ReceivePackCommand command = parseCommand(line, packet.packetIndex(), packet.byteOffset());
                        validateAndAdd(command, commands, seenRefs, packet.packetIndex(), packet.byteOffset());
                    }
                    if (commands.size() > maxCommands) {
                        throw commandError(
                                packet.packetIndex(),
                                packet.byteOffset(),
                                "Command list exceeds the configured limit of " + maxCommands);
                    }
                }
                default -> throw commandError(
                        packet.packetIndex(),
                        packet.byteOffset(),
                        "Unexpected packet kind in receive-pack command section");
            }
        }

        throw GitWireException.of(
                GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND,
                GitWireError.Phase.STRUCTURED_PAYLOAD,
                packetIndex,
                input.readerIndex() - (long) startReaderIndex,
                "Receive-pack command section ended without a flush packet");
    }

    private ReceivePackCommand parseCommand(String line, long packetIndex, long byteOffset) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != SHA1_HEX_LENGTH) {
            throw commandError(packetIndex, byteOffset, "Malformed receive-pack command line");
        }
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (secondSpace != SHA1_HEX_LENGTH * 2 + 1) {
            throw commandError(packetIndex, byteOffset, "Malformed receive-pack command line");
        }

        String oldId = line.substring(0, firstSpace);
        String newId = line.substring(firstSpace + 1, secondSpace);
        String refName = line.substring(secondSpace + 1);

        validateObjectId(oldId, packetIndex, byteOffset);
        validateObjectId(newId, packetIndex, byteOffset);
        validateRefName(refName, packetIndex, byteOffset);

        if (ReceivePackCommand.NULL_ID.equals(newId)) {
            throw commandError(packetIndex, byteOffset, "Delete commands are not supported");
        }

        if (refName.startsWith("refs/tags/")) {
            throw commandError(packetIndex, byteOffset, "Tag ref updates are not supported");
        }

        if (!refName.startsWith("refs/heads/")) {
            throw commandError(packetIndex, byteOffset, "Only refs/heads/* are supported");
        }

        return new ReceivePackCommand(oldId, newId, refName);
    }

    private static void validateAndAdd(
            ReceivePackCommand command,
            List<ReceivePackCommand> commands,
            Set<String> seenRefs,
            long packetIndex,
            long byteOffset) {
        if (!seenRefs.add(command.refName())) {
            throw commandError(packetIndex, byteOffset, "Duplicate ref in command list: " + command.refName());
        }
        commands.add(command);
    }

    private static void validateObjectId(String id, long packetIndex, long byteOffset) {
        if (id.length() != SHA1_HEX_LENGTH) {
            throw commandError(packetIndex, byteOffset, "Object id must be " + SHA1_HEX_LENGTH + " hex characters");
        }
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (!isHexChar(ch)) {
                throw commandError(packetIndex, byteOffset, "Object id contains non-hex character");
            }
        }
    }

    private static void validateRefName(String refName, long packetIndex, long byteOffset) {
        if (refName.isEmpty()) {
            throw commandError(packetIndex, byteOffset, "Ref name must not be empty");
        }
        if (refName.startsWith("/") || refName.endsWith("/")) {
            throw commandError(packetIndex, byteOffset, "Invalid ref name: " + refName);
        }
        if (refName.contains("//") || refName.contains("..") || refName.endsWith(".lock")) {
            throw commandError(packetIndex, byteOffset, "Invalid ref name: " + refName);
        }
        for (int i = 0; i < refName.length(); i++) {
            char ch = refName.charAt(i);
            if (ch < 0x20 || ch == 0x7f || ch == ' ' || ch == '~' || ch == '^'
                    || ch == ':' || ch == '?' || ch == '*' || ch == '[' || ch == '\\') {
                throw commandError(packetIndex, byteOffset, "Invalid ref name: " + refName);
            }
        }
    }

    private static boolean isHexChar(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    private static GitWireException commandError(long packetIndex, long byteOffset, String message) {
        return GitWireException.of(
                GitWireError.Kind.INVALID_RECEIVE_PACK_COMMAND,
                GitWireError.Phase.STRUCTURED_PAYLOAD,
                packetIndex,
                byteOffset,
                message);
    }
}
