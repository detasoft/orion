package pro.deta.orion.event.type;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Getter
@RequiredArgsConstructor
public final class RequestToAclUpdate extends OrionEvent {
    private final String initiator;

    @Override
    protected void appendToStringFields(StringBuilder sb) {
        sb.append(", initiator='").append(initiator).append('\'');
    }
}
