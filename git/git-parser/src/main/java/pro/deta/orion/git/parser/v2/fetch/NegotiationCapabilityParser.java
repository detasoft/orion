package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Accumulates one fetch argument block or legacy negotiation round as an ordered GitCapabilities list.
 * parse returns true while more packets are needed, false on FLUSH or legacy DONE. V2 DONE still requires
 * FLUSH. DELIMITER and RESPONSE_END are invalid within this block; the V2 command envelope is read earlier.
 * After completion another packet is rejected; the next block requires a new parser.
 * capabilities exposes the accumulated list for incremental reads; callers must not mutate it during parsing.
 * Checks argument names, protocol version, value presence, and separators without trimming input.
 * The first legacy want adds the object ID and each trailing capability as separate occurrences.
 * Flags have no value. Object IDs, numeric ranges, ref/filter syntax, request ordering,
 * duplicates, and advertised feature checks belong to the request or negotiation consumer.
 * The result represents a space-separated argument; GitCapabilityValue.wireToken uses capability syntax
 * with '=' and must not be used to serialize this result as a fetch argument.
 */
public final class NegotiationCapabilityParser {
    private final GitProtocolVersion version;
    private final GitCapabilities capabilities = new GitCapabilities();
    private boolean finished;

    public NegotiationCapabilityParser(GitProtocolVersion version) {
        this.version = Objects.requireNonNull(version, "version");
    }

    public GitCapabilities capabilities() {
        return capabilities;
    }

    public boolean parse(GitPktLine packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("Negotiation argument block is complete");
        }
        switch (packet) {
            case GitPktLine.Control.FLUSH -> finished = true;
            case GitPktLine.Control.DELIMITER, GitPktLine.Control.RESPONSE_END ->
                    throw new IOException("Unexpected control packet in negotiation arguments: " + packet);
            case GitPktLine.Data data -> {
                GitCapabilityValue argument = parseLine(data.text());
                if (version != GitProtocolVersion.V2
                        && argument.name().equals(NegotiationCapability.WANT.wireName())) {
                    String[] tokens = argument.value().orElseThrow().split(" ");
                    if (tokens.length > 1 && !capabilities.isEmpty()) {
                        throw new IOException("Capabilities are only allowed on the first want");
                    }
                    var parsed = new GitCapabilities();
                    parsed.add(new GitCapabilityValue(argument.name(), Optional.of(tokens[0])));
                    for (int i = 1; i < tokens.length; i++) {
                        parsed.add(GitCapabilityValue.parse(tokens[i]));
                    }
                    capabilities.addAll(parsed);
                } else {
                    capabilities.add(argument);
                    finished = version != GitProtocolVersion.V2
                            && argument.name().equals(NegotiationCapability.DONE.wireName());
                }
            }
        }
        return !finished;
    }

    private GitCapabilityValue parseLine(String line) throws IOException {
        Objects.requireNonNull(line, "line");
        int separator = line.indexOf(' ');
        String name = separator < 0 ? line : line.substring(0, separator);
        var negotiation = NegotiationCapability.findByWireName(name);
        boolean valued;
        if (negotiation.isPresent()) {
            valued = switch (negotiation.orElseThrow()) {
                case WANT, HAVE, DEEPEN -> true;
                case DONE -> false;
                case WANT_REF -> {
                    requireV2(name);
                    yield true;
                }
            };
        } else {
            GitCapability capability = GitCapability.findByWireName(name)
                    .orElseThrow(() -> new IOException("Invalid negotiation argument: " + name));
            valued = switch (capability) {
                case SHALLOW, DEEPEN_SINCE, DEEPEN_NOT, FILTER -> true;
                case PACKFILE_URIS -> {
                    requireV2(name);
                    yield true;
                }
                case THIN_PACK, OFS_DELTA, INCLUDE_TAG, NO_PROGRESS, WAIT_FOR_DONE,
                     SIDEBAND_ALL, DEEPEN_RELATIVE -> {
                    requireV2(name);
                    yield false;
                }
                default -> throw new IOException("Invalid negotiation argument: " + name);
            };
        }
        if (!valued) {
            if (separator >= 0) {
                throw new IOException("Unexpected value for " + name);
            }
            return new GitCapabilityValue(name, Optional.empty());
        }
        if (separator < 0 || separator == line.length() - 1) {
            throw new IOException("Expected a value for " + name);
        }
        String value = line.substring(separator + 1);
        boolean legacyWant = negotiation.orElse(null) == NegotiationCapability.WANT
                && version != GitProtocolVersion.V2;
        boolean invalidSpacing = legacyWant
                ? value.startsWith(" ") || value.endsWith(" ") || value.contains("  ")
                : value.indexOf(' ') >= 0;
        if (invalidSpacing) {
            throw new IOException("Invalid spacing in " + name);
        }
        return new GitCapabilityValue(name, Optional.of(value));
    }

    private void requireV2(String name) throws IOException {
        if (version != GitProtocolVersion.V2) {
            throw new IOException(name + " requires protocol v2 as a standalone fetch argument");
        }
    }
}
