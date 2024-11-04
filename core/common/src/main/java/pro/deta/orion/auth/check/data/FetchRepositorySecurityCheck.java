package pro.deta.orion.auth.check.data;

import lombok.Data;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.util.Collection;

@Data
public class FetchRepositorySecurityCheck {
    private final Git git;
    private final Repository repository;
    private final Collection<? extends ObjectId> wants;
    private final String repositoryName;
}
