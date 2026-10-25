package pro.deta.orion.event.disruptor;

import lombok.Data;
import pro.deta.orion.event.type.OrionEvent;

@Data
public final class EventHolder {
    private OrionEvent orionEvent;
}
