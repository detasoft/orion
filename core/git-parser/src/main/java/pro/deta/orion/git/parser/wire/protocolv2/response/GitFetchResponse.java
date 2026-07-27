package pro.deta.orion.git.parser.wire.protocolv2.response;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GitFetchResponse(
        Optional<GitFetchAcknowledgments> acknowledgments,
        Optional<GitFetchShallowInfo> shallowInfo,
        List<GitFetchWantedRef> wantedRefs,
        List<GitFetchSection> sections,
        boolean packfileReceived) {

    public GitFetchResponse {
        Objects.requireNonNull(acknowledgments, "acknowledgments");
        Objects.requireNonNull(shallowInfo, "shallowInfo");
        Objects.requireNonNull(wantedRefs, "wantedRefs");
        Objects.requireNonNull(sections, "sections");
        wantedRefs = List.copyOf(wantedRefs);
        sections = List.copyOf(sections);
    }
}
