package pro.deta.orion.auth;

public final class PlainRootTokenAccessForTests {
    private PlainRootTokenAccessForTests() {
    }

    public static PlainRootTokenAccess create() {
        return new PlainRootTokenAccess();
    }
}
