package pro.deta.orion.event;

import pro.deta.orion.event.type.OrionEvent;

public interface OrionEvents {
    void publish(OrionEvent event);
}
