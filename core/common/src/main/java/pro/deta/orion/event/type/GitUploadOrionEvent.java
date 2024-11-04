package pro.deta.orion.event.type;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.eclipse.jgit.storage.pack.PackStatistics;

@EqualsAndHashCode(callSuper = true)
@Data
public final class GitUploadOrionEvent extends OrionEvent {
    private final String repositoryName;
    private final PackStatistics packStatistics;
}
