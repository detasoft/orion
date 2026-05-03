package pro.deta.orion.event.type;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Getter
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

    @Override
    protected void appendToStringFields(StringBuilder sb) {
        sb.append(", repositoryName='").append(repositoryName).append('\'');
        sb.append(", userName='").append(userName).append('\'');
        sb.append(", receiveEventRefs=").append(receiveEventRefs);
    }

    @RequiredArgsConstructor
    @Getter
    public static class GitReceiveEventRef {
        private final String refName;
        private final AnyObjectId oldId;
        private final AnyObjectId newId;
        private final ReceiveCommand.Type type;
        private final ReceiveCommand.Result result;

        @Override
        public String toString() {
            return "GitReceiveEventRef{" +
                    "refName='" + refName + '\'' +
                    ", oldId=" + objectIdName(oldId) +
                    ", newId=" + objectIdName(newId) +
                    ", type=" + type +
                    ", result=" + result +
                    '}';
        }

        private static String objectIdName(AnyObjectId objectId) {
            if (objectId == null) {
                return null;
            }
            return objectId.name();
        }
    }
}
