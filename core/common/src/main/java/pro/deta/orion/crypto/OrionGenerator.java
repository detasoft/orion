package pro.deta.orion.crypto;

import lombok.RequiredArgsConstructor;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;

@RequiredArgsConstructor
public class OrionGenerator {
    private final Argon2BytesGenerator delegate;

    public byte[] generateBytes(char[] input, int size) {
        byte[] out = new byte[size];
        delegate.generateBytes(input, out);
        return out;
    }

    public byte[] generateBytes(byte[] input, int size) {
        byte[] out = new byte[size];
        delegate.generateBytes(input, out);
        return out;
    }
}
