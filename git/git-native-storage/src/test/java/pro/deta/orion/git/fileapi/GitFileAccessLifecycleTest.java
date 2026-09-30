package pro.deta.orion.git.fileapi;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFileAccessLifecycleTest {
    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void closesPackBeforeIndexAndPreservesCleanupFailures(boolean failPack, boolean failIndex) throws Exception {
        List<String> calls = new ArrayList<>();
        IOException packFailure = new IOException("pack close failed");
        IOException indexFailure = new IOException("index discard failed");
        try (InMemoryStorage storage = new InMemoryStorage(); InMemoryIndex owner = new InMemoryIndex()) {
            GitIndexAccess delegate = owner.createAccess(Optional.of(PackId.create()));
            GitIndexAccess index = proxy(GitIndexAccess.class, (ignored, method, args) -> {
                if (method.getName().equals("discard")) {
                    calls.add("index");
                    delegate.discard();
                    if (failIndex) throw indexFailure;
                    return null;
                }
                return invoke(delegate, method, args);
            });
            GitStorageApi observed = proxy(GitStorageApi.class, (ignored, method, args) -> {
                Object result = invoke(storage, method, args);
                if (!method.getName().equals("newPack")) return result;
                PackDataStorage pack = (PackDataStorage) result;
                return proxy(PackDataStorage.class, (unused, operation, arguments) -> {
                    if (operation.getName().equals("close")) {
                        calls.add("pack");
                        assertThat(delegate.snapshotRefs().refs()).isEmpty();
                        pack.close();
                        if (failPack) throw packFailure;
                        return null;
                    }
                    return invoke(pack, operation, arguments);
                });
            });
            NativeGitRepository repository = new NativeGitRepository("demo", observed, owner, "refs/heads/main");
            GitFileAccess access = new GitFileAccess(repository, index, "main", Optional.empty(),
                    "discard", GitCommitAuthor.EMPTY, false);
            try {
                if (failPack || failIndex) {
                    IOException expected = failPack ? packFailure : indexFailure;
                    assertThatThrownBy(access::discard).isSameAs(expected);
                    assertThat(expected.getSuppressed()).containsExactly(
                            failPack && failIndex ? new Throwable[]{indexFailure} : new Throwable[0]);
                } else {
                    access.discard();
                }
                access.discard();
                assertThat(calls).containsExactly("pack", "index");
                assertThatThrownBy(delegate::snapshotRefs).isInstanceOf(IOException.class);
            } finally {
                delegate.discard();
            }
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
