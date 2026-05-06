package pro.deta.orion.event.type;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.git.common.GitUploadStats;

@EqualsAndHashCode(callSuper = true)
@Getter
@RequiredArgsConstructor
public final class GitUploadOrionEvent extends OrionEvent {
    private final String repositoryName;
    private final GitUploadStats uploadStats;

    @Override
    protected void appendToStringFields(StringBuilder sb) {
        sb.append(", repositoryName='").append(repositoryName).append('\'');
        sb.append(", uploadStats=").append(uploadStats);
    }
}
