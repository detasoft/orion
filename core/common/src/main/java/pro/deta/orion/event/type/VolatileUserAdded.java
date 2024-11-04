package pro.deta.orion.event.type;

import lombok.Data;
import lombok.EqualsAndHashCode;
import pro.deta.orion.acl.schema.AccessControl;

import java.util.function.Consumer;

@EqualsAndHashCode(callSuper = true)
@Data
public final class VolatileUserAdded extends OrionEvent {
    private final AccessControl.User userToAdd;
}
