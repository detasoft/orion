package pro.deta.orion.provisioning;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Owns bootstrap-password bytes in wipeable direct storage. Apache MINA requires
 * one transient {@link String} during authentication; Java cannot explicitly
 * clear that immutable value after the single consumer call returns.
 */
public final class BootstrapPassword implements AutoCloseable {
    private final ByteBuffer storage;
    private boolean consumed;

    private BootstrapPassword(ByteBuffer storage) {
        this.storage = storage;
    }

    public static BootstrapPassword copyAndClear(char[] password) {
        if (password == null) {
            throw new IllegalArgumentException("Bootstrap password must not be null");
        }
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(password));
            return fromEncoded(encoded);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Bootstrap password is not valid UTF-8", error);
        } finally {
            Arrays.fill(password, '\0');
            wipe(encoded);
        }
    }

    public static BootstrapPassword copyAndClear(byte[] utf8Password) {
        if (utf8Password == null) {
            throw new IllegalArgumentException("Bootstrap password must not be null");
        }
        CharBuffer decoded = null;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8Password));
            return fromEncoded(ByteBuffer.wrap(utf8Password));
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Bootstrap password is not valid UTF-8", error);
        } finally {
            Arrays.fill(utf8Password, (byte) 0);
            wipe(decoded);
        }
    }

    synchronized <T> T useOnce(PasswordConsumer<T> consumer) throws Exception {
        return useOnce(consumer, cleared -> { });
    }

    @TestOnly
    synchronized <T> T useOnce(
            PasswordConsumer<T> consumer,
            TemporaryClearObserver clearObserver) throws Exception {
        if (consumer == null) {
            throw new IllegalArgumentException("Password consumer must not be null");
        }
        if (clearObserver == null) {
            throw new IllegalArgumentException("Temporary clear observer must not be null");
        }
        if (consumed) {
            throw new IllegalStateException("Bootstrap password has already been cleared");
        }
        consumed = true;
        byte[] encoded = new byte[storage.capacity()];
        CharBuffer characters = null;
        try {
            ByteBuffer source = storage.duplicate();
            source.clear();
            source.get(encoded);
            characters = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded));
            return consumer.accept(characters.toString());
        } finally {
            Arrays.fill(encoded, (byte) 0);
            wipe(characters);
            clearStorage();
            clearObserver.cleared(isCleared(encoded) && isCleared(characters));
        }
    }

    @TestOnly
    synchronized boolean isDirect() {
        return storage.isDirect();
    }

    @TestOnly
    synchronized boolean isCleared() {
        for (int index = 0; index < storage.capacity(); index++) {
            if (storage.get(index) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void close() {
        consumed = true;
        clearStorage();
    }

    @Override
    public String toString() {
        return "BootstrapPassword[protected]";
    }

    private static BootstrapPassword fromEncoded(ByteBuffer encoded) {
        if (!encoded.hasRemaining()) {
            throw new IllegalArgumentException("Bootstrap password must not be empty");
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(encoded.remaining());
        direct.put(encoded.duplicate());
        direct.flip();
        return new BootstrapPassword(direct);
    }

    private void clearStorage() {
        wipe(storage);
    }

    private static void wipe(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        ByteBuffer writable = buffer.duplicate();
        writable.clear();
        for (int index = 0; index < writable.capacity(); index++) {
            writable.put(index, (byte) 0);
        }
    }

    private static void wipe(CharBuffer buffer) {
        if (buffer == null) {
            return;
        }
        CharBuffer writable = buffer.duplicate();
        writable.clear();
        for (int index = 0; index < writable.capacity(); index++) {
            writable.put(index, '\0');
        }
    }

    @FunctionalInterface
    interface PasswordConsumer<T> {
        T accept(String password) throws Exception;
    }

    @FunctionalInterface
    interface TemporaryClearObserver {
        void cleared(boolean cleared);
    }

    private static boolean isCleared(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCleared(CharBuffer characters) {
        if (characters == null) {
            return true;
        }
        CharBuffer readable = characters.duplicate();
        readable.clear();
        for (int index = 0; index < readable.capacity(); index++) {
            if (readable.get(index) != 0) {
                return false;
            }
        }
        return true;
    }
}
