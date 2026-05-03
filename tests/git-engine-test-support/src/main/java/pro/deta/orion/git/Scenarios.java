package pro.deta.orion.git;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import pro.deta.orion.util.stream.AssertiveIOClient;
import pro.deta.orion.util.stream.IOEStreamProvider;
import pro.deta.orion.util.stream.IoConsumer;
import pro.deta.orion.util.stream.ServerIO;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static pro.deta.orion.util.stream.IOTestStreamUtils.testPipeScenario;

@Slf4j
public final class Scenarios {
    private static final int TIMEOUT_SECONDS = 5;
    private static final String FIRST_COMMIT_ID = "a971b22fe44d0a59636d70248c71872250e3687e";
    private static final String FIRST_COMMIT_ID_TOKEN = "{{FIRST_COMMIT_ID}}";
    private static final String FIRST_COMMIT_PUSH_PACK_TOKEN = "{{FIRST_COMMIT_PUSH_PACK}}";
    private static final String FIRST_COMMIT_FETCH_PACK_TOKEN = "{{FIRST_COMMIT_FETCH_PACK}}";
    private static final String REPOSITORY_NAME = "project.git";

    private static final String FIRST_COMMIT_PUSH_PACK = """
            PACK\\00\\00\\00\\02\\00\\00\\00\\03\\92\\0Ax\\9C\\95\\CBA\\0A\\021\\0C@\\D1}O\\91\\BD0$M\\D3X\\90\\C1\\AD\\17p\\DFN\\83\\16\\A6\\0E\\94z\\7F\\E7\\0A\\EE>\\0F\\FE\\1CfPP\\13%\\F2\\A8\\9EC\\14\\8B\\5C\\AA\\0F\\E5*\\95bb\\E1r\\B6*V\\97\\BF\\F3}\\0Cx\\EE\\B9\\B6\\DE\\06<\\E0Vr5\\FD\\DC_=\\B7}\\D9\\8E\\BE\\02)+\\FB\\E4\\85\\E0\\82\\84\\E8N\\EDmN\\FB{tA\\A2\\FB\\01\\E1&-\\CB\\AF\\01x\\9C340031Q042f\\98\\AB<\\FF\\07{\\D3{\\96\\C8.!\\CE\\C9\\F7t\\0E\\FCL\\14\\E7\\00\\00\\92\\07\\0A\\8D3x\\9C362\\04\\00\\011\\00\\97BrM\\D9(JJL\\00\\EA6rE\\88\\90\\02p\\89\\1E\\FA
            """.strip();

    private static final String FIRST_COMMIT_FETCH_PACK = """
            PACK\\00\\00\\00\\02\\00\\00\\00\\0300c4\\01\\92\\0Ax\\9C\\95\\CBA\\0A\\021\\0C@\\D1}O\\91\\BD0$M\\D3X\\90\\C1\\AD\\17p\\DFN\\83\\16\\A6\\0E\\94z\\7F\\E7\\0A\\EE>\\0F\\FE\\1CfPP\\13%\\F2\\A8\\9EC\\14\\8B\\5C\\AA\\0F\\E5*\\95bb\\E1r\\B6*V\\97\\BF\\F3}\\0Cx\\EE\\B9\\B6\\DE\\06<\\E0Vr5\\FD\\DC_=\\B7}\\D9\\8E\\BE\\02)+\\FB\\E4\\85\\E0\\82\\84\\E8N\\EDmN\\FB{tA\\A2\\FB\\01\\E1&-\\CB\\AF\\01x\\9C340031Q042f\\98\\AB<\\FF\\07{\\D3{\\96\\C8.!\\CE\\C9\\F7t\\0E\\FCL\\14\\E7\\00\\00\\92\\07\\0A\\8D3x\\9C362\\04\\00\\011\\00\\97BrM\\D9(JJL\\00\\EA6rE\\88\\90\\02p\\89\\1E\\FA
            """.strip();

    private static final String LIST_EMPTY_REPOSITORY_REFS = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A0014ref-prefix HEAD\\0A001bref-prefix refs/heads/\\0A001aref-prefix refs/tags/\\0A0000
            S:0000
            C:0000
            """);

    private static final String PUSH_FIRST_COMMIT_WITH_RECEIVE_PACK_ADVERTISEMENT = script("""
            S:009d0000000000000000000000000000000000000000 capabilities^{}\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:00950000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/heads/master\\00 report-status side-band-64k agent=git/2.42.00000{{FIRST_COMMIT_PUSH_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0030\\01000eunpack ok\\0A0019ok refs/heads/master\\0A00000000
            """);

    private static final String PUSH_FIRST_COMMIT_WITHOUT_RECEIVE_PACK_ADVERTISEMENT = script("""
            C:00950000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/heads/master\\00 report-status side-band-64k agent=git/2.42.00000{{FIRST_COMMIT_PUSH_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0030\\01000eunpack ok\\0A0019ok refs/heads/master\\0A00000000
            """);

    private static final String LIST_MASTER_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0021ref-prefix refs/heads/master\\0A001aref-prefix refs/tags/\\0A0000
            S:003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0000
            """);

    private static final String FETCH_MASTER_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A0014ref-prefix HEAD\\0A001bref-prefix refs/heads/\\0A001aref-prefix refs/tags/\\0A0000
            S:0052{{FIRST_COMMIT_ID}} HEAD symref-target:refs/heads/master\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0011command=fetch0001000dthin-pack000dofs-delta0032want {{FIRST_COMMIT_ID}}\\0A0032want {{FIRST_COMMIT_ID}}\\0A0009done\\0A0000
            S:000dpackfile\\0A001c\\02Counting objects: 1   \\0D001f\\02Counting objects: 3, done\\0A0024\\02Finding sources:  33% (1/3)   \\0D0024\\02Finding sources:  67% (2/3)   \\0D0024\\02Finding sources: 100% (3/3)   \\0D0021\\02Finding sources: 100% (3/3)\\0A0022\\02Getting sizes:  50% (1/2)   \\0D0022\\02Getting sizes: 100% (2/2)   \\0D001f\\02Getting sizes: 100% (2/2)\\0A0011\\01{{FIRST_COMMIT_FETCH_PACK}}002b\\02Total 3 (delta 0), reused 3 (delta 0)\\0A0000
            """);

    private Scenarios() {
    }

    public static void fetchEmptyRepository(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions, listEmptyRepositoryRefs());
    }

    public static void fetchEmptyRepository(GitCommandServer server, SoftAssertions assertions) {
        runSteps(server, assertions, listEmptyRepositoryRefs());
    }

    public static void pushFirstCommitThenListAndFetch(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                listMasterAfterPush(),
                fetchMasterAfterPush());
    }

    public static void pushFirstCommitThenListAndFetch(GitCommandServer server, SoftAssertions assertions) {
        runSteps(server, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                listMasterAfterPush(),
                fetchMasterAfterPush());
    }

    public static void pushFirstCommitThenListAndFetchFromReceive(GitCommandServer server, SoftAssertions assertions) {
        runSteps(server, assertions,
                pushFirstCommitWithCapabilities(),
                listMasterAfterPush(),
                fetchMasterAfterPush());
    }

    public static void pushFirstCommitThenListAndFetchWithoutReceivePackAdvertisement(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithoutReceivePackAdvertisement(),
                listMasterAfterPush(),
                fetchMasterAfterPush());
    }

    private static ProtocolStep listEmptyRepositoryRefs() {
        return uploadPackStep("list refs in empty repository", LIST_EMPTY_REPOSITORY_REFS);
    }

    private static ProtocolStep pushFirstCommitWithCapabilities() {
        return receivePackStep("push first commit after receive-pack advertisement", PUSH_FIRST_COMMIT_WITH_RECEIVE_PACK_ADVERTISEMENT);
    }

    private static ProtocolStep pushFirstCommitWithoutReceivePackAdvertisement() {
        return receivePackStep("push first commit without receive-pack advertisement", PUSH_FIRST_COMMIT_WITHOUT_RECEIVE_PACK_ADVERTISEMENT);
    }

    private static ProtocolStep listMasterAfterPush() {
        return uploadPackStep("list master ref after push", LIST_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep fetchMasterAfterPush() {
        return uploadPackStep("fetch master after push", FETCH_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep uploadPackStep(String name, String transcript) {
        return new ProtocolStep(name, "git-upload-pack /" + REPOSITORY_NAME, List.of("version=2"), transcript, Scenarios::uploadPack);
    }

    private static ProtocolStep receivePackStep(String name, String transcript) {
        return new ProtocolStep(name, "git-receive-pack /" + REPOSITORY_NAME, List.of(), transcript, Scenarios::receivePack);
    }

    private static void runSteps(Repository repository, SoftAssertions assertions, ProtocolStep... steps) {
        for (ProtocolStep step : steps) {
            runStep(repository, assertions, step);
            assertions.assertAll();
        }
    }

    private static void runSteps(GitCommandServer server, SoftAssertions assertions, ProtocolStep... steps) {
        for (ProtocolStep step : steps) {
            runStep(server, assertions, step);
            assertions.assertAll();
        }
    }

    private static void runStep(Repository repository, SoftAssertions assertions, ProtocolStep step) {
        log.debug("Running Git protocol step '{}' against {}", step.name(), repository.getIdentifier());

        try {
            testPipeScenario(
                    new AssertiveIOClient(step.transcript(), assertions),
                    step.server(repository),
                    assertions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running Git protocol step: " + step.name(), e);
        }
    }

    private static void runStep(GitCommandServer server, SoftAssertions assertions, ProtocolStep step) {
        log.debug("Running Git protocol step '{}' through git server command '{}'", step.name(), step.commandLine());

        try {
            testPipeScenario(
                    new AssertiveIOClient(step.transcript(), assertions),
                    server.serverFor(step.commandLine(), step.extraProperties()),
                    assertions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running Git protocol step: " + step.name(), e);
        }
    }

    private static IoConsumer<ServerIO> uploadPack(Repository repository) {
        return serverIO -> {
            UploadPack uploadPack = new UploadPack(repository);
            uploadPack.setTimeout(TIMEOUT_SECONDS);
            uploadPack.setExtraParameters(Set.of("version=2"));
            upload(uploadPack, serverIO.ioEStreams());
        };
    }

    private static IoConsumer<ServerIO> receivePack(Repository repository) {
        return serverIO -> {
            ReceivePack receivePack = new ReceivePack(repository);
            receivePack.setTimeout(TIMEOUT_SECONDS);
            receivePack.receive(
                    serverIO.ioEStreams().getInputStream(),
                    serverIO.ioEStreams().getOutputStream(),
                    serverIO.ioEStreams().getErrorStream());
        };
    }

    private static void upload(UploadPack uploadPack, IOEStreamProvider streams) throws IOException {
        try {
            uploadPack.uploadWithExceptionPropagation(
                    streams.getInputStream(),
                    streams.getOutputStream(),
                    streams.getErrorStream());
        } catch (Exception e) {
            log.error("Exception on {}", uploadPack.getRepository().getDirectory(), e);
            writeErrorIntoOS(streams.getOutputStream(), e.getMessage());
        }
    }

    private static void writeErrorIntoOS(OutputStream os, String message) {
        try {
            PacketLineOut pktOut = new PacketLineOut(os);
            pktOut.writeString("ERR " + message);
            pktOut.end();
        } catch (Exception e) {
            log.error("Error while error writing.", e);
        }
    }

    private static String script(String transcript) {
        return transcript
                .replace(FIRST_COMMIT_ID_TOKEN, FIRST_COMMIT_ID)
                .replace(FIRST_COMMIT_PUSH_PACK_TOKEN, FIRST_COMMIT_PUSH_PACK)
                .replace(FIRST_COMMIT_FETCH_PACK_TOKEN, FIRST_COMMIT_FETCH_PACK);
    }

    private record ProtocolStep(
            String name,
            String commandLine,
            List<String> extraProperties,
            String transcript,
            Function<Repository, IoConsumer<ServerIO>> serverFactory) {

        IoConsumer<ServerIO> server(Repository repository) {
            return serverFactory.apply(repository);
        }
    }

    @FunctionalInterface
    public interface GitCommandServer {
        IoConsumer<ServerIO> serverFor(String commandLine, List<String> extraProperties);
    }
}
