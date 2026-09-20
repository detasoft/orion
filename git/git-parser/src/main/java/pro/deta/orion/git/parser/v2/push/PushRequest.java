package pro.deta.orion.git.parser.v2.push;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record PushRequest(List<RefUpdate> updates, GitCapabilities capabilities) {
    private static final String ZERO_ID = "0".repeat(40);

    public PushRequest {
        updates = List.copyOf(updates);
        capabilities = new GitCapabilities(capabilities);
    }

    public static PushRequest parse(GitProtocolContext.Reader reader, GitCapabilities advertised)
            throws IOException {
        List<RefUpdate> updates = new ArrayList<>();
        GitCapabilities capabilities = new GitCapabilities();
        Set<RefId> refs = new HashSet<>();
        for (;;) {
            GitPktLine packet = reader.readGitPktLine();
            if (packet == GitPktLine.Control.FLUSH) {
                return new PushRequest(updates, capabilities);
            }
            if (!(packet instanceof GitPktLine.Data data)) {
                throw new IOException("Expected a push command or flush");
            }
            byte[] content = data.content();
            int separator = -1;
            for (int index = 0; index < content.length; index++) {
                if (content[index] == 0) {
                    if (separator >= 0 || !updates.isEmpty()) {
                        throw new IOException("Capabilities are only allowed on the first push command");
                    }
                    separator = index;
                }
            }
            String command;
            if (separator >= 0) {
                if (separator > 0 && content[separator - 1] == '\n') {
                    throw new IOException("Unexpected newline before push capabilities");
                }
                command = new GitPktLine.Data(Arrays.copyOf(content, separator)).text();
                String tokens = new GitPktLine.Data(
                        Arrays.copyOfRange(content, separator + 1, content.length)).text();
                parseCapabilities(tokens, advertised, capabilities);
            } else {
                command = data.text();
            }
            if (command.startsWith("shallow ")) {
                throw new IOException("Shallow push is not supported");
            }
            RefUpdate update = parseUpdate(command);
            if (!refs.add(update.ref())) {
                throw new IOException("Duplicate push ref: " + update.ref());
            }
            if (update.newId().isEmpty() && !advertised.has(GitCapability.DELETE_REFS)) {
                throw new IOException("Ref deletion was not advertised");
            }
            updates.add(update);
        }
    }

    public boolean requiresPack() {
        for (RefUpdate update : updates) {
            if (update.newId().isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static RefUpdate parseUpdate(String line) throws IOException {
        if (line.length() <= 82 || line.charAt(40) != ' ' || line.charAt(81) != ' ') {
            throw new IOException("Invalid push command");
        }
        try {
            RefId ref = new RefId(line.substring(82));
            ref.requireFullName();
            return new RefUpdate(ref, parseId(line.substring(0, 40)), parseId(line.substring(41, 81)));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid push command", error);
        }
    }

    private static Optional<ObjectId> parseId(String hex) {
        return hex.equals(ZERO_ID) ? Optional.empty() : Optional.of(new ObjectId(hex));
    }

    private static void parseCapabilities(String tokens, GitCapabilities advertised, GitCapabilities result)
            throws IOException {
        if (tokens.isEmpty()) {
            return;
        }
        for (String token : tokens.split(" ", -1)) {
            GitCapabilityValue value = GitCapabilityValue.parse(token, GitHashAlgorithm.SHA1);
            GitCapability capability = value.capability()
                    .orElseThrow(() -> new IOException("Unsupported push capability: " + value.name()));
            if (!advertised.has(capability)) {
                throw new IOException("Push capability was not advertised: " + value.name());
            }
            boolean hasValue = switch (capability) {
                case AGENT, SESSION_ID, OBJECT_FORMAT -> true;
                case REPORT_STATUS, REPORT_STATUS_V2, SIDE_BAND_64K, OFS_DELTA, QUIET, ATOMIC,
                     DELETE_REFS -> false;
                default -> throw new IOException("Unsupported push capability: " + value.name());
            };
            if (hasValue != value.value().isPresent() || result.has(capability)) {
                throw new IOException("Invalid or repeated push capability: " + value.name());
            }
            result.add(value);
        }
    }
}
