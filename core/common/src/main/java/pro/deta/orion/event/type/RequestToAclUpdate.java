package pro.deta.orion.event.type;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public final class RequestToAclUpdate extends OrionEvent {
    private final String initiator;
}
