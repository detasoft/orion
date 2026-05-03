package pro.deta.orion.auth;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.auth.check.data.FetchRepositorySecurityCheck;

import java.net.SocketAddress;


@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Permission<O> {
    private final String name;
    private final Class<O> cls;

    public static final Permission<String> REPOSITORY_CREATE = new Permission<>("REPOSITORY_CREATE", String.class);
    public static final Permission<String> REPOSITORY_READ = new Permission<>("REPOSITORY_READ", String.class);
    public static final Permission<FetchRepositorySecurityCheck> REPOSITORY_FETCH = new Permission<>("REPOSITORY_FETCH", FetchRepositorySecurityCheck.class);
    public static final Permission<String> REPOSITORY_WRITE = new Permission<>("REPOSITORY_WRITE", String.class);

    public static final Permission<String> BRANCH_PULL = new Permission<>("BRANCH_PULL", String.class);
    public static final Permission<String> BRANCH_PUSH = new Permission<>("BRANCH_PUSH", String.class);
    public static final Permission<String> BRANCH_PUSH_FORCE = new Permission<>("BRANCH_PUSH_FORCE", String.class);

    public static final Permission<String> REQUEST_ID = new Permission<>("REQUEST_ID", String.class);
    public static final Permission<String> APPLICATION_SHUTDOWN = new Permission<>("APPLICATION_SHUTDOWN", String.class);

    public static final Permission<SocketAddress> CLIENT_SOCKET_ADDRESS = new Permission<>("CLIENT_SOCKET_ADDRESS", SocketAddress.class);

    public static final Permission<UserIdentity> USER_IDENTITY = new Permission<>("USER_IDENTITY", UserIdentity.class);
}
