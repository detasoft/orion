package pro.deta.orion.git;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import pro.deta.orion.util.Pair;
import pro.deta.orion.util.stream.AssertiveIOClient;
import pro.deta.orion.util.stream.DirectionalByteArrayOutputStream;
import pro.deta.orion.util.stream.IOEStreamProvider;
import pro.deta.orion.util.stream.IoConsumer;
import pro.deta.orion.util.stream.ServerIO;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static pro.deta.orion.util.stream.IOTestStreamUtils.testPipeScenario;

@Slf4j
public final class Scenarios {
    private static final int TIMEOUT_SECONDS = 5;
    private static final String FIRST_COMMIT_ID = "a971b22fe44d0a59636d70248c71872250e3687e";
    private static final String SECOND_ROOT_COMMIT_ID = "a9646354f2c01add76096d798125d21904f7e7d6";
    private static final String FAST_FORWARD_COMMIT_ID = "0d9a22a125ec36da1abd773a5be62c4347381416";
    private static final String ANNOTATED_TAG_ID = "fddfe5e20990f3e98b772156e4838ae5d81b75f3";
    private static final String EMPTY_TREE_ID = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
    private static final String FIRST_COMMIT_ID_TOKEN = "{{FIRST_COMMIT_ID}}";
    private static final String SECOND_ROOT_COMMIT_ID_TOKEN = "{{SECOND_ROOT_COMMIT_ID}}";
    private static final String FAST_FORWARD_COMMIT_ID_TOKEN = "{{FAST_FORWARD_COMMIT_ID}}";
    private static final String ANNOTATED_TAG_ID_TOKEN = "{{ANNOTATED_TAG_ID}}";
    private static final String FIRST_COMMIT_PUSH_PACK_TOKEN = "{{FIRST_COMMIT_PUSH_PACK}}";
    private static final String FIRST_COMMIT_FETCH_PACK_TOKEN = "{{FIRST_COMMIT_FETCH_PACK}}";
    private static final String EMPTY_RECEIVE_PACK_TOKEN = "{{EMPTY_RECEIVE_PACK}}";
    private static final String REPOSITORY_NAME = "project.git";
    private static final String SECOND_ROOT_COMMIT = """
            tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904
            author Orion <orion@example.test> 0 +0000
            committer Orion <orion@example.test> 0 +0000
            
            second root
            """;
    private static final String FAST_FORWARD_COMMIT = """
            tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904
            parent a971b22fe44d0a59636d70248c71872250e3687e
            author Orion <orion@example.test> 1 +0000
            committer Orion <orion@example.test> 1 +0000
            
            second commit
            """;
    private static final String ANNOTATED_TAG = """
            object a971b22fe44d0a59636d70248c71872250e3687e
            type commit
            tag v1-annotated
            tagger Orion <orion@example.test> 0 +0000
            
            version 1
            """;

    private static final String FIRST_COMMIT_PUSH_PACK = """
            PACK\\00\\00\\00\\02\\00\\00\\00\\03\\92\\0Ax\\9C\\95\\CBA\\0A\\021\\0C@\\D1}O\\91\\BD0$M\\D3X\\90\\C1\\AD\\17p\\DFN\\83\\16\\A6\\0E\\94z\\7F\\E7\\0A\\EE>\\0F\\FE\\1CfPP\\13%\\F2\\A8\\9EC\\14\\8B\\5C\\AA\\0F\\E5*\\95bb\\E1r\\B6*V\\97\\BF\\F3}\\0Cx\\EE\\B9\\B6\\DE\\06<\\E0Vr5\\FD\\DC_=\\B7}\\D9\\8E\\BE\\02)+\\FB\\E4\\85\\E0\\82\\84\\E8N\\EDmN\\FB{tA\\A2\\FB\\01\\E1&-\\CB\\AF\\01x\\9C340031Q042f\\98\\AB<\\FF\\07{\\D3{\\96\\C8.!\\CE\\C9\\F7t\\0E\\FCL\\14\\E7\\00\\00\\92\\07\\0A\\8D3x\\9C362\\04\\00\\011\\00\\97BrM\\D9(JJL\\00\\EA6rE\\88\\90\\02p\\89\\1E\\FA
            """.strip();

    private static final String FIRST_COMMIT_FETCH_PACK = """
            PACK\\00\\00\\00\\02\\00\\00\\00\\0300c4\\01\\92\\0Ax\\9C\\95\\CBA\\0A\\021\\0C@\\D1}O\\91\\BD0$M\\D3X\\90\\C1\\AD\\17p\\DFN\\83\\16\\A6\\0E\\94z\\7F\\E7\\0A\\EE>\\0F\\FE\\1CfPP\\13%\\F2\\A8\\9EC\\14\\8B\\5C\\AA\\0F\\E5*\\95bb\\E1r\\B6*V\\97\\BF\\F3}\\0Cx\\EE\\B9\\B6\\DE\\06<\\E0Vr5\\FD\\DC_=\\B7}\\D9\\8E\\BE\\02)+\\FB\\E4\\85\\E0\\82\\84\\E8N\\EDmN\\FB{tA\\A2\\FB\\01\\E1&-\\CB\\AF\\01x\\9C340031Q042f\\98\\AB<\\FF\\07{\\D3{\\96\\C8.!\\CE\\C9\\F7t\\0E\\FCL\\14\\E7\\00\\00\\92\\07\\0A\\8D3x\\9C362\\04\\00\\011\\00\\97BrM\\D9(JJL\\00\\EA6rE\\88\\90\\02p\\89\\1E\\FA
            """.strip();

    private static final String EMPTY_RECEIVE_PACK = """
            PACK\\00\\00\\00\\02\\00\\00\\00\\00\\02\\9D\\08\\82;\\D8\\A8\\EA\\B5\\10\\ADj\\C7\\5C\\82<\\FD>\\D3\\1E
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

    private static final String CREATE_FEATURE_BRANCH_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:00970000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/heads/feature\\00 report-status side-band-64k agent=git/2.42.0\\0A0000{{FIRST_COMMIT_PUSH_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0031\\01000eunpack ok\\0A001aok refs/heads/feature\\0A00000000
            """);

    private static final String CREATE_SECOND_ROOT_FEATURE_BRANCH_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:00970000000000000000000000000000000000000000 {{SECOND_ROOT_COMMIT_ID}} refs/heads/feature\\00 report-status side-band-64k agent=git/2.42.0\\0A0000{{EMPTY_RECEIVE_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0031\\01000eunpack ok\\0A001aok refs/heads/feature\\0A00000000
            """);

    private static final String CREATE_TAG_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:00910000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/tags/v1\\00 report-status side-band-64k agent=git/2.42.0\\0A0000{{FIRST_COMMIT_PUSH_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A002b\\01000eunpack ok\\0A0014ok refs/tags/v1\\0A00000000
            """);

    private static final String DELETE_MASTER_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:0096{{FIRST_COMMIT_ID}} 0000000000000000000000000000000000000000 refs/heads/master\\00 report-status side-band-64k agent=git/2.42.0\\0A0000
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0030\\01000eunpack ok\\0A0019ok refs/heads/master\\0A00000000
            """);

    private static final String FAST_FORWARD_UPDATE_MASTER_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:0096{{FIRST_COMMIT_ID}} {{FAST_FORWARD_COMMIT_ID}} refs/heads/master\\00 report-status side-band-64k agent=git/2.42.0\\0A0000{{EMPTY_RECEIVE_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0030\\01000eunpack ok\\0A0019ok refs/heads/master\\0A00000000
            """);

    private static final String FORCE_PUSH_SECOND_ROOT_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:0096{{FIRST_COMMIT_ID}} {{SECOND_ROOT_COMMIT_ID}} refs/heads/master\\00 report-status side-band-64k agent=git/2.42.0\\0A0000{{EMPTY_RECEIVE_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0030\\01000eunpack ok\\0A0019ok refs/heads/master\\0A00000000
            """);

    private static final String REJECT_NON_FAST_FORWARD_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:0096{{FIRST_COMMIT_ID}} {{SECOND_ROOT_COMMIT_ID}} refs/heads/master\\00 report-status side-band-64k agent=git/2.42.0\\0A0000{{EMPTY_RECEIVE_PACK}}
            S:0041\\01000eunpack ok\\0A002ang refs/heads/master non-fast forward\\0A00000000
            """);

    private static final String CREATE_FEATURE_BRANCH_AND_TAG_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:00970000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/heads/feature\\00 report-status side-band-64k agent=git/2.42.0\\0A00630000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/tags/v1\\0A0000{{FIRST_COMMIT_PUSH_PACK}}
            S:0028\\02Updating references:  50% (1/2)   \\0D0028\\02Updating references: 100% (2/2)   \\0D0025\\02Updating references: 100% (2/2)\\0A0045\\01000eunpack ok\\0A001aok refs/heads/feature\\0A0014ok refs/tags/v1\\0A00000000
            """);

    private static final String CREATE_FEATURE_BRANCH_AND_TAG_ATOMIC_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:009e0000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/heads/feature\\00 report-status side-band-64k atomic agent=git/2.42.0\\0A00630000000000000000000000000000000000000000 {{FIRST_COMMIT_ID}} refs/tags/v1\\0A0000{{FIRST_COMMIT_PUSH_PACK}}
            S:0045\\01000eunpack ok\\0A001aok refs/heads/feature\\0A0014ok refs/tags/v1\\0A00000000
            """);

    private static final String CREATE_ANNOTATED_TAG_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0000
            C:009b0000000000000000000000000000000000000000 {{ANNOTATED_TAG_ID}} refs/tags/v1-annotated\\00 report-status side-band-64k agent=git/2.42.0\\0A0000{{EMPTY_RECEIVE_PACK}}
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0035\\01000eunpack ok\\0A001eok refs/tags/v1-annotated\\0A00000000
            """);

    private static final String DELETE_ANNOTATED_TAG_AFTER_PUSH = script("""
            S:009f{{FIRST_COMMIT_ID}} refs/heads/master\\00 side-band-64k delete-refs report-status quiet atomic ofs-delta agent=JGit/7.0.0.202409031743-r\\0A0044{{ANNOTATED_TAG_ID}} refs/tags/v1-annotated\\0A0000
            C:009b{{ANNOTATED_TAG_ID}} 0000000000000000000000000000000000000000 refs/tags/v1-annotated\\00 report-status side-band-64k agent=git/2.42.0\\0A0000
            S:0028\\02Updating references: 100% (1/1)   \\0D0025\\02Updating references: 100% (1/1)\\0A0035\\01000eunpack ok\\0A001eok refs/tags/v1-annotated\\0A00000000
            """);

    private static final String LIST_MASTER_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0021ref-prefix refs/heads/master\\0A001aref-prefix refs/tags/\\0A0000
            S:003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0000
            """);

    private static final String LIST_MASTER_AFTER_FAST_FORWARD = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0021ref-prefix refs/heads/master\\0A001aref-prefix refs/tags/\\0A0000
            S:003f{{FAST_FORWARD_COMMIT_ID}} refs/heads/master\\0A0000
            C:0000
            """);

    private static final String LIST_MASTER_AFTER_FORCE_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0021ref-prefix refs/heads/master\\0A001aref-prefix refs/tags/\\0A0000
            S:003f{{SECOND_ROOT_COMMIT_ID}} refs/heads/master\\0A0000
            C:0000
            """);

    private static final String LIST_HEADS_AFTER_FEATURE_BRANCH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0000
            S:0040{{FIRST_COMMIT_ID}} refs/heads/feature\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0000
            """);

    private static final String LIST_HEADS_AFTER_SECOND_ROOT_FEATURE_BRANCH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0000
            S:0040{{SECOND_ROOT_COMMIT_ID}} refs/heads/feature\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0000
            """);

    private static final String LIST_HEAD_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A0014ref-prefix HEAD\\0A0000
            S:0052{{FIRST_COMMIT_ID}} HEAD symref-target:refs/heads/master\\0A0000
            C:0000
            """);

    private static final String LIST_TAGS_AFTER_TAG_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001aref-prefix refs/tags/\\0A0000
            S:003a{{FIRST_COMMIT_ID}} refs/tags/v1\\0A0000
            C:0000
            """);

    private static final String LIST_TAGS_AFTER_ANNOTATED_TAG_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001aref-prefix refs/tags/\\0A0000
            S:0074{{ANNOTATED_TAG_ID}} refs/tags/v1-annotated peeled:{{FIRST_COMMIT_ID}}\\0A0000
            C:0000
            """);

    private static final String LIST_UNKNOWN_BRANCH_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A0029ref-prefix refs/heads/does-not-exist\\0A0000
            S:0000
            C:0000
            """);

    private static final String FETCH_MASTER_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A0014ref-prefix HEAD\\0A001bref-prefix refs/heads/\\0A001aref-prefix refs/tags/\\0A0000
            S:0052{{FIRST_COMMIT_ID}} HEAD symref-target:refs/heads/master\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0011command=fetch0001000dthin-pack000dofs-delta0032want {{FIRST_COMMIT_ID}}\\0A0032want {{FIRST_COMMIT_ID}}\\0A0009done\\0A0000
            S:000dpackfile\\0A001c\\02Counting objects: 1   \\0D001f\\02Counting objects: 3, done\\0A0024\\02Finding sources:  33% (1/3)   \\0D0024\\02Finding sources:  67% (2/3)   \\0D0024\\02Finding sources: 100% (3/3)   \\0D0021\\02Finding sources: 100% (3/3)\\0A0022\\02Getting sizes:  50% (1/2)   \\0D0022\\02Getting sizes: 100% (2/2)   \\0D001f\\02Getting sizes: 100% (2/2)\\0A0011\\01{{FIRST_COMMIT_FETCH_PACK}}002b\\02Total 3 (delta 0), reused 3 (delta 0)\\0A0000
            """);

    private static final String FETCH_MASTER_WITH_HAVE_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0011command=fetch0001000dthin-pack000dofs-delta0032want {{FIRST_COMMIT_ID}}\\0A0032have {{FIRST_COMMIT_ID}}\\0A0009done\\0A0000
            S:000dpackfile\\0A0011\\01PACK\\00\\00\\00\\02\\00\\00\\00\\000019\\01\\02\\9D\\08\\82;\\D8\\A8\\EA\\B5\\10\\ADj\\C7\\5C\\82<\\FD>\\D3\\1E002b\\02Total 0 (delta 0), reused 0 (delta 0)\\0A0000
            """);

    private static final String SHALLOW_FETCH_MASTER_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0011command=fetch0001000dthin-pack000dofs-delta000ddeepen 1\\0A0032want {{FIRST_COMMIT_ID}}\\0A0009done\\0A0000
            S:0011shallow-info\\0A0035shallow {{FIRST_COMMIT_ID}}\\0A0001000dpackfile\\0A001c\\02Counting objects: 1   \\0D001f\\02Counting objects: 3, done\\0A0024\\02Finding sources:  33% (1/3)   \\0D0024\\02Finding sources:  67% (2/3)   \\0D0024\\02Finding sources: 100% (3/3)   \\0D0021\\02Finding sources: 100% (3/3)\\0A0022\\02Getting sizes:  50% (1/2)   \\0D0022\\02Getting sizes: 100% (2/2)   \\0D001f\\02Getting sizes: 100% (2/2)\\0A0011\\01{{FIRST_COMMIT_FETCH_PACK}}002b\\02Total 3 (delta 0), reused 3 (delta 0)\\0A0000
            """);

    private static final String FETCH_UNKNOWN_OBJECT_AFTER_PUSH = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0011command=fetch0001000dthin-pack000dofs-delta0032want 1111111111111111111111111111111111111111\\0A0009done\\0A0000
            """);

    private static final String FETCH_SECOND_ROOT_FEATURE_BRANCH_DENIED = script("""
            S:000eversion 2\\0A000cls-refs\\0A0012fetch=shallow\\0A0012server-option\\0A0000
            C:0014command=ls-refs\\0A00010009peel\\0A000csymrefs\\0A001bref-prefix refs/heads/\\0A0000
            S:0040{{SECOND_ROOT_COMMIT_ID}} refs/heads/feature\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0011command=fetch0001000dthin-pack000dofs-delta0032want {{SECOND_ROOT_COMMIT_ID}}\\0A0009done\\0A0000
            S:0015ERR ACCESS_DENIED0000
            """);

    private static final String CLASSIC_LIST_EMPTY_REPOSITORY_REFS = script("""
            S:00c70000000000000000000000000000000000000000 capabilities^{}\\00 include-tag multi_ack_detailed multi_ack ofs-delta side-band side-band-64k thin-pack no-progress shallow agent=JGit/7.0.0.202409031743-r\\0A0000
            """);

    private static final String CLASSIC_LIST_REFS_AFTER_PUSH = script("""
            S:00da{{FIRST_COMMIT_ID}} HEAD\\00 include-tag multi_ack_detailed multi_ack ofs-delta side-band side-band-64k thin-pack no-progress shallow agent=JGit/7.0.0.202409031743-r symref=HEAD:refs/heads/master\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            """);

    private static final String CLASSIC_FETCH_MASTER_AFTER_PUSH = script("""
            S:00da{{FIRST_COMMIT_ID}} HEAD\\00 include-tag multi_ack_detailed multi_ack ofs-delta side-band side-band-64k thin-pack no-progress shallow agent=JGit/7.0.0.202409031743-r symref=HEAD:refs/heads/master\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            C:0067want {{FIRST_COMMIT_ID}} multi_ack_detailed side-band-64k thin-pack ofs-delta\\0A00000009done\\0A
            S:0008NAK\\0A001c\\02Counting objects: 1   \\0D001f\\02Counting objects: 3, done\\0A0024\\02Finding sources:  33% (1/3)   \\0D0024\\02Finding sources:  67% (2/3)   \\0D0024\\02Finding sources: 100% (3/3)   \\0D0021\\02Finding sources: 100% (3/3)\\0A0022\\02Getting sizes:  50% (1/2)   \\0D0022\\02Getting sizes: 100% (2/2)   \\0D001f\\02Getting sizes: 100% (2/2)\\0A0011\\01{{FIRST_COMMIT_FETCH_PACK}}002b\\02Total 3 (delta 0), reused 3 (delta 0)\\0A0000
            """);

    private static final String CLASSIC_LIST_REFS_AFTER_FEATURE_BRANCH = script("""
            S:00da{{FIRST_COMMIT_ID}} HEAD\\00 include-tag multi_ack_detailed multi_ack ofs-delta side-band side-band-64k thin-pack no-progress shallow agent=JGit/7.0.0.202409031743-r symref=HEAD:refs/heads/master\\0A0040{{FIRST_COMMIT_ID}} refs/heads/feature\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0000
            """);

    private static final String CLASSIC_LIST_REFS_AFTER_TAG = script("""
            S:00da{{FIRST_COMMIT_ID}} HEAD\\00 include-tag multi_ack_detailed multi_ack ofs-delta side-band side-band-64k thin-pack no-progress shallow agent=JGit/7.0.0.202409031743-r symref=HEAD:refs/heads/master\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A003a{{FIRST_COMMIT_ID}} refs/tags/v1\\0A0000
            """);

    private static final String CLASSIC_LIST_REFS_AFTER_ANNOTATED_TAG = script("""
            S:00da{{FIRST_COMMIT_ID}} HEAD\\00 include-tag multi_ack_detailed multi_ack ofs-delta side-band side-band-64k thin-pack no-progress shallow agent=JGit/7.0.0.202409031743-r symref=HEAD:refs/heads/master\\0A003f{{FIRST_COMMIT_ID}} refs/heads/master\\0A0044{{ANNOTATED_TAG_ID}} refs/tags/v1-annotated\\0A0047{{FIRST_COMMIT_ID}} refs/tags/v1-annotated^{}\\0A0000
            """);

    private static final String CLASSIC_LIST_REFS_AFTER_FAST_FORWARD = script("""
            S:00da{{FAST_FORWARD_COMMIT_ID}} HEAD\\00 include-tag multi_ack_detailed multi_ack ofs-delta side-band side-band-64k thin-pack no-progress shallow agent=JGit/7.0.0.202409031743-r symref=HEAD:refs/heads/master\\0A003f{{FAST_FORWARD_COMMIT_ID}} refs/heads/master\\0A0000
            """);

    private Scenarios() {
    }

    public static void fetchEmptyRepository(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions, listEmptyRepositoryRefs());
    }

    public static void fetchEmptyRepositoryClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions, classicListEmptyRepositoryRefs());
    }

    public static void advertiseProtocolV2Capabilities(Repository repository, SoftAssertions assertions) {
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

    public static void pushFirstCommitThenFetch(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                fetchMasterAfterPush());
    }

    public static void pushFirstCommitThenListClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                classicListEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                classicListRefsAfterPush());
    }

    public static void pushFirstCommitThenFetchClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                classicListEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                classicFetchMasterAfterPush());
    }

    public static void pushFirstCommitThenCreateSecondBranchAndListClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                classicListEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                createFeatureBranchAfterPush(),
                classicListRefsAfterFeatureBranch());
    }

    public static void pushFirstCommitThenCreateTagAndListClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                classicListEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                createTagAfterPush(),
                classicListRefsAfterTag());
    }

    public static void pushFirstCommitThenCreateAnnotatedTagAndListClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                classicListEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertAnnotatedTag(repository);
        runSteps(repository, assertions,
                createAnnotatedTagAfterPush(),
                classicListRefsAfterAnnotatedTag());
    }

    public static void pushFirstCommitThenDeleteMasterAndListClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                classicListEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                deleteMasterAfterPush(),
                classicListEmptyRepositoryRefs());
    }

    public static void pushFirstCommitThenFastForwardMasterAndListClassic(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                classicListEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertFastForwardCommit(repository);
        runSteps(repository, assertions,
                fastForwardUpdateMasterAfterPush(),
                classicListRefsAfterFastForward());
    }

    public static void pushFirstCommitThenListAndFetch(GitCommandServer server, SoftAssertions assertions) {
        runSteps(server, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                listMasterAfterPush(),
                fetchMasterAfterPush());
    }

    public static void pushFirstCommitThenFastForwardMasterAndListIt(GitCommandServer server, Repository repository, SoftAssertions assertions) {
        runSteps(server, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertFastForwardCommit(repository);
        runSteps(server, assertions,
                fastForwardUpdateMasterAfterPush(),
                listMasterAfterFastForward());
    }

    public static void pushFirstCommitThenCreateSecondRootFeatureBranch(GitCommandServer server, Repository repository, SoftAssertions assertions) {
        runSteps(server, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertSecondRootCommit(repository);
        runSteps(server, assertions,
                createSecondRootFeatureBranchAfterPush(),
                listHeadsAfterSecondRootFeatureBranch());
    }

    public static void fetchSecondRootFeatureBranchDenied(GitCommandServer server, SoftAssertions assertions) {
        runSteps(server, assertions, fetchSecondRootFeatureBranchDenied());
    }

    public static void pushFirstCommitThenDeleteMasterAndListRefs(GitCommandServer server, SoftAssertions assertions) {
        runSteps(server, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                deleteMasterAfterPush(),
                listEmptyRepositoryRefs());
    }

    public static void pushFeatureBranchAndTagInOneReceivePack(GitCommandServer server, SoftAssertions assertions) {
        runSteps(server, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                createFeatureBranchAndTagAfterPush(),
                listHeadsAfterFeatureBranch(),
                listTagsAfterTagPush());
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

    public static void pushFirstCommitThenListHead(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                listHeadAfterPush());
    }

    public static void pushFirstCommitThenListUnknownBranch(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                listUnknownBranchAfterPush());
    }

    public static void pushFirstCommitThenCreateSecondBranchAndListHeads(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                createFeatureBranchAfterPush(),
                listHeadsAfterFeatureBranch());
    }

    public static void pushFirstCommitThenCreateTagAndListTags(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                createTagAfterPush(),
                listTagsAfterTagPush());
    }

    public static void pushFirstCommitThenDeleteMasterAndListRefs(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                deleteMasterAfterPush(),
                listEmptyRepositoryRefs());
    }

    public static void pushFirstCommitThenFastForwardMasterAndListIt(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertFastForwardCommit(repository);
        runSteps(repository, assertions,
                fastForwardUpdateMasterAfterPush(),
                listMasterAfterFastForward());
    }

    public static void pushFirstCommitThenForcePushAndListMaster(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertSecondRootCommit(repository);
        runSteps(repository, assertions,
                forcePushSecondRootAfterPush(),
                listMasterAfterForcePush());
    }

    public static void rejectNonFastForwardPushWhenConfigured(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertSecondRootCommit(repository);
        configureDenyNonFastForwards(repository, true);
        runSteps(repository, assertions,
                rejectNonFastForwardAfterPush(),
                listMasterAfterPush());
    }

    public static void pushFeatureBranchAndTagInOneReceivePack(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                createFeatureBranchAndTagAfterPush(),
                listHeadsAfterFeatureBranch(),
                listTagsAfterTagPush());
    }

    public static void pushFeatureBranchAndTagAtomicallyInOneReceivePack(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                createFeatureBranchAndTagAtomicallyAfterPush(),
                listHeadsAfterFeatureBranch(),
                listTagsAfterTagPush());
    }

    public static void pushFirstCommitThenCreateAnnotatedTagAndListPeeledTag(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertAnnotatedTag(repository);
        runSteps(repository, assertions,
                createAnnotatedTagAfterPush(),
                listTagsAfterAnnotatedTagPush());
    }

    public static void pushFirstCommitThenCreateAndDeleteAnnotatedTag(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities());
        insertAnnotatedTag(repository);
        runSteps(repository, assertions,
                createAnnotatedTagAfterPush(),
                listTagsAfterAnnotatedTagPush(),
                deleteAnnotatedTagAfterPush(),
                listMasterAfterPush());
    }

    public static void pushFirstCommitThenFetchWithHave(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                fetchMasterWithHaveAfterPush());
    }

    public static void pushFirstCommitThenFetchShallow(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                shallowFetchMasterAfterPush());
    }

    public static void pushFirstCommitThenFetchUnknownObject(Repository repository, SoftAssertions assertions) {
        runSteps(repository, assertions,
                listEmptyRepositoryRefs(),
                pushFirstCommitWithCapabilities(),
                fetchUnknownObjectAfterPush());
    }

    private static ProtocolStep listEmptyRepositoryRefs() {
        return uploadPackStep("list refs in empty repository", LIST_EMPTY_REPOSITORY_REFS);
    }

    private static ProtocolStep classicListEmptyRepositoryRefs() {
        return classicUploadPackStep("list refs in empty repository with classic protocol", CLASSIC_LIST_EMPTY_REPOSITORY_REFS);
    }

    private static ProtocolStep pushFirstCommitWithCapabilities() {
        return receivePackStep("push first commit after receive-pack advertisement", PUSH_FIRST_COMMIT_WITH_RECEIVE_PACK_ADVERTISEMENT);
    }

    private static ProtocolStep pushFirstCommitWithoutReceivePackAdvertisement() {
        return receivePackStep("push first commit without receive-pack advertisement", PUSH_FIRST_COMMIT_WITHOUT_RECEIVE_PACK_ADVERTISEMENT);
    }

    private static ProtocolStep createFeatureBranchAfterPush() {
        return receivePackStep("create feature branch after push", CREATE_FEATURE_BRANCH_AFTER_PUSH);
    }

    private static ProtocolStep createSecondRootFeatureBranchAfterPush() {
        return receivePackStep("create second-root feature branch after push", CREATE_SECOND_ROOT_FEATURE_BRANCH_AFTER_PUSH);
    }

    private static ProtocolStep createTagAfterPush() {
        return receivePackStep("create tag after push", CREATE_TAG_AFTER_PUSH);
    }

    private static ProtocolStep deleteMasterAfterPush() {
        return receivePackStep("delete master after push", DELETE_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep fastForwardUpdateMasterAfterPush() {
        return receivePackStep("fast-forward master after push", FAST_FORWARD_UPDATE_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep forcePushSecondRootAfterPush() {
        return receivePackStep("force push second root commit after push", FORCE_PUSH_SECOND_ROOT_AFTER_PUSH);
    }

    private static ProtocolStep rejectNonFastForwardAfterPush() {
        return receivePackStep("reject non-fast-forward update after push", REJECT_NON_FAST_FORWARD_AFTER_PUSH);
    }

    private static ProtocolStep createFeatureBranchAndTagAfterPush() {
        return receivePackStep("create feature branch and tag in one receive-pack", CREATE_FEATURE_BRANCH_AND_TAG_AFTER_PUSH);
    }

    private static ProtocolStep createFeatureBranchAndTagAtomicallyAfterPush() {
        return receivePackStep("create feature branch and tag atomically in one receive-pack", CREATE_FEATURE_BRANCH_AND_TAG_ATOMIC_AFTER_PUSH);
    }

    private static ProtocolStep createAnnotatedTagAfterPush() {
        return receivePackStep("create annotated tag after push", CREATE_ANNOTATED_TAG_AFTER_PUSH);
    }

    private static ProtocolStep deleteAnnotatedTagAfterPush() {
        return receivePackStep("delete annotated tag after push", DELETE_ANNOTATED_TAG_AFTER_PUSH);
    }

    private static ProtocolStep listMasterAfterPush() {
        return uploadPackStep("list master ref after push", LIST_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep listMasterAfterFastForward() {
        return uploadPackStep("list master ref after fast-forward", LIST_MASTER_AFTER_FAST_FORWARD);
    }

    private static ProtocolStep listMasterAfterForcePush() {
        return uploadPackStep("list master ref after force push", LIST_MASTER_AFTER_FORCE_PUSH);
    }

    private static ProtocolStep listHeadsAfterFeatureBranch() {
        return uploadPackStep("list heads after feature branch", LIST_HEADS_AFTER_FEATURE_BRANCH);
    }

    private static ProtocolStep listHeadsAfterSecondRootFeatureBranch() {
        return uploadPackStep("list heads after second-root feature branch", LIST_HEADS_AFTER_SECOND_ROOT_FEATURE_BRANCH);
    }

    private static ProtocolStep listHeadAfterPush() {
        return uploadPackStep("list HEAD symref after push", LIST_HEAD_AFTER_PUSH);
    }

    private static ProtocolStep listTagsAfterTagPush() {
        return uploadPackStep("list tags after tag push", LIST_TAGS_AFTER_TAG_PUSH);
    }

    private static ProtocolStep listTagsAfterAnnotatedTagPush() {
        return uploadPackStep("list tags after annotated tag push", LIST_TAGS_AFTER_ANNOTATED_TAG_PUSH);
    }

    private static ProtocolStep listUnknownBranchAfterPush() {
        return uploadPackStep("list missing branch ref after push", LIST_UNKNOWN_BRANCH_AFTER_PUSH);
    }

    private static ProtocolStep fetchMasterAfterPush() {
        return uploadPackStep("fetch master after push", FETCH_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep fetchMasterWithHaveAfterPush() {
        return uploadPackStep("fetch master with existing have after push", FETCH_MASTER_WITH_HAVE_AFTER_PUSH);
    }

    private static ProtocolStep shallowFetchMasterAfterPush() {
        return uploadPackStep("shallow fetch master after push", SHALLOW_FETCH_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep fetchUnknownObjectAfterPush() {
        return uploadPackStep("fetch unknown object after push", FETCH_UNKNOWN_OBJECT_AFTER_PUSH);
    }

    private static ProtocolStep fetchSecondRootFeatureBranchDenied() {
        return uploadPackStep("fetch second-root feature branch denied", FETCH_SECOND_ROOT_FEATURE_BRANCH_DENIED);
    }

    private static ProtocolStep classicListRefsAfterPush() {
        return classicUploadPackStep("list refs after push with classic protocol", CLASSIC_LIST_REFS_AFTER_PUSH);
    }

    private static ProtocolStep classicFetchMasterAfterPush() {
        return classicUploadPackStep("fetch master after push with classic protocol", CLASSIC_FETCH_MASTER_AFTER_PUSH);
    }

    private static ProtocolStep classicListRefsAfterFeatureBranch() {
        return classicUploadPackStep("list refs after feature branch with classic protocol", CLASSIC_LIST_REFS_AFTER_FEATURE_BRANCH);
    }

    private static ProtocolStep classicListRefsAfterTag() {
        return classicUploadPackStep("list refs after tag with classic protocol", CLASSIC_LIST_REFS_AFTER_TAG);
    }

    private static ProtocolStep classicListRefsAfterAnnotatedTag() {
        return classicUploadPackStep("list refs after annotated tag with classic protocol", CLASSIC_LIST_REFS_AFTER_ANNOTATED_TAG);
    }

    private static ProtocolStep classicListRefsAfterFastForward() {
        return classicUploadPackStep("list refs after fast-forward with classic protocol", CLASSIC_LIST_REFS_AFTER_FAST_FORWARD);
    }

    private static ProtocolStep uploadPackStep(String name, String transcript) {
        return new ProtocolStep(name, "git-upload-pack /" + REPOSITORY_NAME, List.of("version=2"), transcript, Scenarios::uploadPack);
    }

    private static ProtocolStep classicUploadPackStep(String name, String transcript) {
        return new ProtocolStep(name, "git-upload-pack /" + REPOSITORY_NAME, List.of(), transcript, Scenarios::classicUploadPack);
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
            Pair<StringBuilder, List<DirectionalByteArrayOutputStream>> actualTranscript = testPipeScenario(
                    new AssertiveIOClient(step.transcript(), assertions),
                    step.server(repository),
                    assertions);
            assertTranscriptMatches(step, actualTranscript.getFirst(), assertions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running Git protocol step: " + step.name(), e);
        }
    }

    private static void runStep(GitCommandServer server, SoftAssertions assertions, ProtocolStep step) {
        log.debug("Running Git protocol step '{}' through git server command '{}'", step.name(), step.commandLine());

        try {
            Pair<StringBuilder, List<DirectionalByteArrayOutputStream>> actualTranscript = testPipeScenario(
                    new AssertiveIOClient(step.transcript(), assertions),
                    server.serverFor(step.commandLine(), step.extraProperties()),
                    assertions);
            assertTranscriptMatches(step, actualTranscript.getFirst(), assertions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running Git protocol step: " + step.name(), e);
        }
    }

    private static void assertTranscriptMatches(ProtocolStep step, StringBuilder actualTranscript, SoftAssertions assertions) {
        assertions.assertThat(actualTranscript.toString().stripLeading())
                .describedAs("Full Git protocol transcript for step '%s'", step.name())
                .isEqualTo(step.transcript());
    }

    private static IoConsumer<ServerIO> uploadPack(Repository repository) {
        return serverIO -> {
            UploadPack uploadPack = new UploadPack(repository);
            uploadPack.setTimeout(TIMEOUT_SECONDS);
            uploadPack.setExtraParameters(Set.of("version=2"));
            upload(uploadPack, serverIO.ioEStreams());
        };
    }

    private static IoConsumer<ServerIO> classicUploadPack(Repository repository) {
        return serverIO -> {
            UploadPack uploadPack = new UploadPack(repository);
            uploadPack.setTimeout(TIMEOUT_SECONDS);
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
            writeProtocolError(streams.getOutputStream(), e.getMessage());
        }
    }

    private static void writeProtocolError(OutputStream outputStream, String message) {
        try {
            PacketLineOut packetLineOut = new PacketLineOut(outputStream);
            packetLineOut.writeString("ERR " + message);
            packetLineOut.end();
        } catch (Exception e) {
            log.error("Failed to write Git protocol error response", e);
        }
    }

    private static String script(String transcript) {
        return transcript
                .replace(FIRST_COMMIT_ID_TOKEN, FIRST_COMMIT_ID)
                .replace(SECOND_ROOT_COMMIT_ID_TOKEN, SECOND_ROOT_COMMIT_ID)
                .replace(FAST_FORWARD_COMMIT_ID_TOKEN, FAST_FORWARD_COMMIT_ID)
                .replace(ANNOTATED_TAG_ID_TOKEN, ANNOTATED_TAG_ID)
                .replace(FIRST_COMMIT_PUSH_PACK_TOKEN, FIRST_COMMIT_PUSH_PACK)
                .replace(FIRST_COMMIT_FETCH_PACK_TOKEN, FIRST_COMMIT_FETCH_PACK)
                .replace(EMPTY_RECEIVE_PACK_TOKEN, EMPTY_RECEIVE_PACK);
    }

    private static void insertSecondRootCommit(Repository repository) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId treeId = inserter.insert(Constants.OBJ_TREE, new byte[0]);
            if (!EMPTY_TREE_ID.equals(treeId.name())) {
                throw new IllegalStateException("Unexpected empty tree id: " + treeId.name());
            }
            ObjectId commitId = inserter.insert(Constants.OBJ_COMMIT, SECOND_ROOT_COMMIT.getBytes(StandardCharsets.UTF_8));
            if (!SECOND_ROOT_COMMIT_ID.equals(commitId.name())) {
                throw new IllegalStateException("Unexpected second root commit id: " + commitId.name());
            }
            inserter.flush();
        } catch (IOException e) {
            throw new RuntimeException("Cannot insert second root commit into " + repository.getIdentifier(), e);
        }
    }

    private static void insertFastForwardCommit(Repository repository) {
        insertExpectedObject(repository, Constants.OBJ_TREE, new byte[0], EMPTY_TREE_ID);
        insertExpectedObject(repository, Constants.OBJ_COMMIT, FAST_FORWARD_COMMIT.getBytes(StandardCharsets.UTF_8), FAST_FORWARD_COMMIT_ID);
    }

    private static void insertAnnotatedTag(Repository repository) {
        insertExpectedObject(repository, Constants.OBJ_TAG, ANNOTATED_TAG.getBytes(StandardCharsets.UTF_8), ANNOTATED_TAG_ID);
    }

    private static void insertExpectedObject(Repository repository, int objectType, byte[] data, String expectedId) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId objectId = inserter.insert(objectType, data);
            if (!expectedId.equals(objectId.name())) {
                throw new IllegalStateException("Unexpected inserted object id: " + objectId.name());
            }
            inserter.flush();
        } catch (IOException e) {
            throw new RuntimeException("Cannot insert test object into " + repository.getIdentifier(), e);
        }
    }

    private static void configureDenyNonFastForwards(Repository repository, boolean value) {
        try {
            StoredConfig config = repository.getConfig();
            config.setBoolean("receive", null, "denyNonFastForwards", value);
            config.save();
        } catch (IOException e) {
            throw new RuntimeException("Cannot configure receive.denyNonFastForwards in " + repository.getIdentifier(), e);
        }
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
