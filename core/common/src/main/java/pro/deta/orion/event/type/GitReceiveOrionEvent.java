package pro.deta.orion.event.type;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.pack.PackStatistics;
import org.eclipse.jgit.transport.ReceiveCommand;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public final class GitReceiveOrionEvent extends OrionEvent {
    private final String repositoryName;
    private final List<GitReceiveEventRef> receiveEventRefs = new ArrayList<>();
    private final String userName;

    public GitReceiveOrionEvent(String repositoryName, String userName) {
        this.repositoryName = repositoryName;
        this.userName = userName;
    }

    public void addReceiveEventRef(String refName, ObjectId oldId, ObjectId newId, ReceiveCommand.Type type, ReceiveCommand.Result result) {
        receiveEventRefs.add(new GitReceiveOrionEvent.GitReceiveEventRef(refName, oldId, newId, type, result));
    }

    @RequiredArgsConstructor
    @Getter
    public static class GitReceiveEventRef {
        private final String refName;
        private final AnyObjectId oldId;
        private final AnyObjectId newId;
        private final ReceiveCommand.Type type;
        private final ReceiveCommand.Result result;
    }
}
