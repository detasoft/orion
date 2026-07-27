package pro.deta.orion.git.parser.wire.receivepack;

import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ReceivePackCapabilityResolver {

    public ReceivePackCapabilityResolution resolve(
            Set<ReceivePackCapability> serverSupported,
            GitCapabilitySet clientRequested) {
        Objects.requireNonNull(serverSupported, "serverSupported");
        Objects.requireNonNull(clientRequested, "clientRequested");

        List<ReceivePackCapability> selected = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (GitCapability cap : clientRequested.asList()) {
            Optional<ReceivePackCapability> known = ReceivePackCapability.fromWireName(cap.name());
            if (known.isPresent()) {
                ReceivePackCapability serverCap = known.get();
                if (serverSupported.contains(serverCap)) {
                    if (isClientValueAccepted(serverCap, cap)) {
                        selected.add(serverCap);
                    } else {
                        rejected.add(cap.rawToken());
                    }
                } else if (serverCap.requiresExplicitRequest()) {
                    rejected.add(cap.name());
                } else {
                    ignored.add(cap.name());
                }
            } else {
                rejected.add(cap.name());
            }
        }

        return new ReceivePackCapabilityResolution(selected, ignored, rejected);
    }

    private static boolean isClientValueAccepted(ReceivePackCapability serverCap, GitCapability cap) {
        if (!serverCap.valueCapable() && cap.value().isPresent()) {
            return false;
        }
        if (serverCap == ReceivePackCapability.OBJECT_FORMAT) {
            return cap.value().map("sha1"::equals).orElse(true);
        }
        return true;
    }
}
