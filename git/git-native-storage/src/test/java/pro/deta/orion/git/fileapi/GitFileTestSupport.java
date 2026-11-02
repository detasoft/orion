package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class GitFileTestSupport {
    private GitFileTestSupport() {}

    public static void initialize(NativeGitRepository repository, String branch) throws Exception {
        String ref = repository.ref(branch).value();
        if (repository.refs().containsKey(ref)) return;
        ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
        String content = "tree " + tree.toHex() + "\n"
                + "author Test <test@example.invalid> 1 +0000\n"
                + "committer Test <test@example.invalid> 1 +0000\n\nseed\n";
        ObjectId commit = repository.writeObject(GitObjectType.COMMIT, content.getBytes(StandardCharsets.UTF_8));
        GitOperationException.requireSuccess(List.of(repository.updateRef(ref, "0".repeat(40), commit.toHex())));
    }
}
