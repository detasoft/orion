package pro.deta.orion.event.type;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.acl.schema.AccessControl;

@EqualsAndHashCode(callSuper = true)
@Getter
@RequiredArgsConstructor
public final class VolatileUserAdded extends OrionEvent {
    private final AccessControl.User userToAdd;

    @Override
    protected void appendToStringFields(StringBuilder sb) {
        sb.append(", userToAdd=").append(userToAdd);
    }
}
