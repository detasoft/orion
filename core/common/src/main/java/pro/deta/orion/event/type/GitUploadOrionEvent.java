package pro.deta.orion.event.type;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.storage.pack.PackStatistics;

@EqualsAndHashCode(callSuper = true)
@Getter
@RequiredArgsConstructor
public final class GitUploadOrionEvent extends OrionEvent {
    private final String repositoryName;
    private final PackStatistics packStatistics;

    @Override
    protected void appendToStringFields(StringBuilder sb) {
        sb.append(", repositoryName='").append(repositoryName).append('\'');
        sb.append(", packStatistics=").append(packStatistics);
    }
}
