package pro.deta.orion.event.type;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicBoolean;

@Getter
@Setter
public sealed abstract class OrionEvent permits GitReceiveOrionEvent, GitUploadOrionEvent, VolatileUserAdded, RequestToAclUpdate {
    private final AtomicBoolean processed = new AtomicBoolean(false);

    public void setProcessed() {
        processed.set(true);
    }

    public boolean isProcessed() {
        return processed.get();
    }
}
