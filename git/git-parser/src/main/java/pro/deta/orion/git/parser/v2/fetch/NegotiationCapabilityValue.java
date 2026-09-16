package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.util.Objects;

/**
 * One decoded fetch argument or negotiation message, with a known capability name and its value.
 * NegotiationCapability.parse extracts the value; a bare name has an empty value. The remainder stays intact,
 * including Unicode ref names and the capabilities following a legacy want. Consumers validate the value
 * for their protocol and command. Capability tokens such as agent=value still use GitCapability.parse.
 */
public record NegotiationCapabilityValue(GitCapability cap, String value) {
    public NegotiationCapabilityValue {
        Objects.requireNonNull(cap, "cap");
        Objects.requireNonNull(value, "value");
    }
}
