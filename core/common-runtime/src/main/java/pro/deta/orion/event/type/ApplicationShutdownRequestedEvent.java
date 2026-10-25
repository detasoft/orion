package pro.deta.orion.event.type;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Getter
@RequiredArgsConstructor
public final class ApplicationShutdownRequestedEvent extends OrionEvent {
    private final String source;

    @Override
    protected void appendToStringFields(StringBuilder sb) {
        sb.append(", source='").append(source).append('\'');
    }
}
