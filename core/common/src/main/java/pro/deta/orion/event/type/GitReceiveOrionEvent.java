package pro.deta.orion.event.type;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import pro.deta.orion.git.common.GitRefUpdate;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Getter
public final class GitReceiveOrionEvent extends OrionEvent {
    private final String repositoryName;
    private final List<GitRefUpdate> receiveEventRefs = new ArrayList<>();
    private final String userName;

    public GitReceiveOrionEvent(String repositoryName, String userName) {
        this.repositoryName = repositoryName;
        this.userName = userName;
    }

    public void addReceiveEventRef(GitRefUpdate ref) {
        receiveEventRefs.add(ref);
    }

    @Override
    protected void appendToStringFields(StringBuilder sb) {
        sb.append(", repositoryName='").append(repositoryName).append('\'');
        sb.append(", userName='").append(userName).append('\'');
        sb.append(", receiveEventRefs=").append(receiveEventRefs);
    }
}
