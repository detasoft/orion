package pro.deta.orion.git.parser.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GitCapabilityParser {

    public GitCapabilitySet parseAdvertisementLine(String advertisementLine) {
        Objects.requireNonNull(advertisementLine, "advertisementLine");
        int capabilityStart = advertisementLine.indexOf('\0');
        if (capabilityStart < 0 || capabilityStart == advertisementLine.length() - 1) {
            return new GitCapabilitySet(List.of());
        }
        return parseCapabilityList(advertisementLine.substring(capabilityStart + 1));
    }

    public GitCapabilitySet parseCapabilityList(String capabilityList) {
        Objects.requireNonNull(capabilityList, "capabilityList");
        if (capabilityList.isBlank()) {
            return new GitCapabilitySet(List.of());
        }
        List<GitCapability> capabilities = new ArrayList<>();
        for (String token : capabilityList.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            capabilities.add(GitCapability.parse(token));
        }
        return new GitCapabilitySet(capabilities);
    }

    public GitCapabilitySet parseProtocolV2Lines(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<GitCapability> capabilities = new ArrayList<>();
        for (String line : lines) {
            capabilities.add(parseProtocolV2Line(line));
        }
        return new GitCapabilitySet(capabilities);
    }

    public GitCapability parseProtocolV2Line(String line) {
        Objects.requireNonNull(line, "line");
        return GitCapability.parse(GitPktLineReader.stripLineEnding(line));
    }
}
