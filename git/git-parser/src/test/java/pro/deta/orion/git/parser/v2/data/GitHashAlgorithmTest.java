package pro.deta.orion.git.parser.v2.data;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GitHashAlgorithmTest {
    @Test
    void hashesUsingTheSelectedAlgorithm() {
        byte[] input = "abc".getBytes(StandardCharsets.US_ASCII);
        assertThat(HexFormat.of().formatHex(GitHashAlgorithm.SHA1.newDigest().digest(input)))
                .isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
        assertThat(HexFormat.of().formatHex(GitHashAlgorithm.SHA256.newDigest().digest(input)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void eachDigestOwnsItsAccumulatedBytes() {
        MessageDigest first = GitHashAlgorithm.SHA1.newDigest();
        MessageDigest second = GitHashAlgorithm.SHA1.newDigest();
        first.update((byte) 'a');
        second.update("abc".getBytes(StandardCharsets.US_ASCII));
        first.update("bc".getBytes(StandardCharsets.US_ASCII));
        assertThat(first).isNotSameAs(second);
        assertThat(first.digest()).isEqualTo(second.digest());
    }
}
