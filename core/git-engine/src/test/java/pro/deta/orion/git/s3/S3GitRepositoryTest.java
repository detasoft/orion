package pro.deta.orion.git.s3;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import pro.deta.orion.git.BaseOrionTest;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.util.Pair;
import pro.deta.orion.util.stream.*;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.util.stream.IOTestStreamUtils.testPipeScenario;

@Slf4j
@ExtendWith(S3ServerRule.class)
@Setter
public class S3GitRepositoryTest extends BaseOrionTest implements S3ServerRule.AbstractClientAware {

    private AbstractClient abstractClient;


    @Test
    public void testBucketExists() {
        assertThat(abstractClient.listBuckets()).contains(abstractClient.bucketName);
    }

    private void runTestInRepo(BiConsumer<Repository, SoftAssertions> repositoryConsumer) throws IOException {
        try {
            abstractClient.removeBucket();
        } catch (Exception e) {
            System.out.println(1);
        }
        try (S3Repository r = new S3Repository(abstractClient)) {
            r.create(true);
            SoftAssertions.assertSoftly(sa -> {
                repositoryConsumer.accept(r, sa);
            });
        }
    }

    @Test
    @Disabled("investigation needed")
    void simplePatchToReceive() throws IOException {
        runTestInRepo((r,sa) -> {
            AssertiveIOClient fetchEmptyRepo = new AssertiveIOClient("""
                S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
                C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A0014ref-prefix HEAD\\0A001bref-prefix refs/heads/\\0A001aref-prefix refs/tags/\\0A0000
                S:0000
                C:0000
                """, sa);

            AssertiveIOClient uploadFirstPack = new AssertiveIOClient("""
                C:00950000000000000000000000000000000000000000 a971b22fe44d0a59636d70248c71872250e3687e refs/heads/master\\00 report-status side-band-64k agent=git/2.42.00000PACK\\00\\00\\00\\02\\00\\00\\00\\03\\92\\0Ax\\9C\\95\\CBA\\0A\\021\\0C@\\D1}O\\91\\BD0$M\\D3X\\90\\C1\\AD\\17p\\DFN\\83\\16\\A6\\0E\\94z\\7F\\E7\\0A\\EE>\\0F\\FE\\1CfPP\\13%\\F2\\A8\\9EC\\14\\8B\\5C\\AA\\0F\\E5*\\95bb\\E1r\\B6*V\\97\\BF\\F3}\\0Cx\\EE\\B9\\B6\\DE\\06<\\E0Vr5\\FD\\DC_=\\B7}\\D9\\8E\\BE\\02)+\\FB\\E4\\85\\E0\\82\\84\\E8N\\EDmN\\FB{tA\\A2\\FB\\01\\E1&-\\CB\\AF\\01x\\9C340031Q042f\\98\\AB<\\FF\\07{\\D3{\\96\\C8.!\\CE\\C9\\F7t\\0E\\FCL\\14\\E7\\00\\00\\92\\07\\0A\\8D3x\\9C362\\04\\00\\011\\00\\97BrM\\D9(JJL\\00\\EA6rE\\88\\90\\02p\\89\\1E\\FA
                S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0030\\01000eunpack ok\\0A0019ok refs/heads/master\\0A00000000
                """, sa);

            AssertiveIOClient verifyState = new AssertiveIOClient("""
                S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
                C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0021ref-prefix refs/heads/master\\0A001aref-prefix refs/tags/\\0A0000
                S:003fa971b22fe44d0a59636d70248c71872250e3687e refs/heads/master\\0A0000
                C:0000
                """, sa);

            AssertiveIOClient cloneRepo = new AssertiveIOClient("""
                S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
                C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A0014ref-prefix HEAD\\0A001bref-prefix refs/heads/\\0A001aref-prefix refs/tags/\\0A0000
                S:0052a971b22fe44d0a59636d70248c71872250e3687e HEAD symref-target:refs/heads/master\\0A003fa971b22fe44d0a59636d70248c71872250e3687e refs/heads/master\\0A0000
                C:0011command=fetch0001000dthin-pack000dofs-delta0032want a971b22fe44d0a59636d70248c71872250e3687e\\0A0032want a971b22fe44d0a59636d70248c71872250e3687e\\0A0009done\\0A0000
                S:000dpackfile\\0A001c\\02Counting objects: 1   \\0D001f\\02Counting objects: 3, done\\0A0024\\02Finding sources:  33% (1/3)   \\0D0024\\02Finding sources:  67% (2/3)   \\0D0024\\02Finding sources: 100% (3/3)   \\0D0021\\02Finding sources: 100% (3/3)\\0A0022\\02Getting sizes:  50% (1/2)   \\0D0022\\02Getting sizes: 100% (2/2)   \\0D001f\\02Getting sizes: 100% (2/2)\\0A0011\\01PACK\\00\\00\\00\\02\\00\\00\\00\\0300c4\\01\\92\\0Ax\\9C\\95\\CBA\\0A\\021\\0C@\\D1}O\\91\\BD0$M\\D3X\\90\\C1\\AD\\17p\\DFN\\83\\16\\A6\\0E\\94z\\7F\\E7\\0A\\EE>\\0F\\FE\\1CfPP\\13%\\F2\\A8\\9EC\\14\\8B\\5C\\AA\\0F\\E5*\\95bb\\E1r\\B6*V\\97\\BF\\F3}\\0Cx\\EE\\B9\\B6\\DE\\06<\\E0Vr5\\FD\\DC_=\\B7}\\D9\\8E\\BE\\02)+\\FB\\E4\\85\\E0\\82\\84\\E8N\\EDmN\\FB{tA\\A2\\FB\\01\\E1&-\\CB\\AF\\01x\\9C340031Q042f\\98\\AB<\\FF\\07{\\D3{\\96\\C8.!\\CE\\C9\\F7t\\0E\\FCL\\14\\E7\\00\\00\\92\\07\\0A\\8D3x\\9C362\\04\\00\\011\\00\\97BrM\\D9(JJL\\00\\EA6rE\\88\\90\\02p\\89\\1E\\FA002b\\02Total 3 (delta 0), reused 3 (delta 0)\\0A0000
                """, sa);


            try {
                testPipeScenario(fetchEmptyRepo, uploadPack(r), sa);
                sa.assertAll();
                testPipeScenario(uploadFirstPack, receivePack(r), sa);
                sa.assertAll();
                testPipeScenario(verifyState, uploadPack(r), sa);
                sa.assertAll();
                testPipeScenario(cloneRepo, uploadPack(r), sa);
                sa.assertAll();
            } catch (Exception e) {
                log.error("Error", e);
                throw new RuntimeException(e);
            }
        });
    }

    public IoConsumer<ServerIO> uploadPack(Repository r) {
        return serverIO -> {
            UploadPack uploadPack = GitUtils.uploadPack(r, Set.of("version=2"));
            GitUtils.upload(uploadPack, serverIO.ioEStreams());
        };
    }

    public IoConsumer<ServerIO> receivePack(Repository r) {
        return serverIO -> {
            ReceivePack receivePack = GitUtils.receivePack(r);
            GitUtils.receive(receivePack, serverIO.ioEStreams());
        };
    }
}
