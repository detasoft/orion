package pro.deta.orion.git.workflow;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GitWorkflowScenarios {
    private static final String MAIN = "refs/heads/main";
    private static final String FEATURE = "refs/heads/feature";
    private static final String TAG = "refs/tags/v1";
    private static final String README = "README.md";
    private static final String FEATURE_FILE = "feature.txt";
    private static final String INITIAL_CONTENT = "initial\n";
    private static final Set<GitCapability> WRITE = Set.of(
            GitCapability.INITIALIZE, GitCapability.COMMIT, GitCapability.PUSH);
    private static final Set<GitCapability> CLONE = Set.of(
            GitCapability.INITIALIZE, GitCapability.COMMIT, GitCapability.PUSH, GitCapability.CLONE);
    private static final Set<GitCapability> PULL = Set.of(
            GitCapability.INITIALIZE, GitCapability.COMMIT, GitCapability.PUSH,
            GitCapability.CLONE, GitCapability.FAST_FORWARD_PULL);
    private static final Set<GitCapability> FETCH = Set.of(
            GitCapability.INITIALIZE, GitCapability.COMMIT, GitCapability.PUSH,
            GitCapability.CLONE, GitCapability.FETCH);
    private static final List<GitScenario> CATALOG = List.of(
            scenario("empty-repository-discovery", Set.of(GitCapability.INITIALIZE, GitCapability.FETCH),
                    state(Map.of(), Map.of()), GitWorkflowScenarios::emptyRepositoryDiscovery),
            scenario("initial-push-and-clone", CLONE,
                    initialState(), GitWorkflowScenarios::initialPushAndClone),
            scenario("empty-clone-and-first-push", CLONE,
                    initialState(), GitWorkflowScenarios::emptyCloneAndFirstPush),
            scenario("clone-multiple-commit-history", CLONE, threeCommitState("third\n"),
                    GitWorkflowScenarios::cloneMultipleCommitHistory),
            scenario("fast-forward-push-and-pull", PULL, twoCommitState("updated\n"),
                    GitWorkflowScenarios::fastForwardPushAndPull),
            scenario("alternating-two-client-round-trip", PULL, threeCommitState("first-again\n"),
                    GitWorkflowScenarios::alternatingTwoClientRoundTrip),
            scenario("multi-commit-single-push", WRITE, threeCommitState("third\n"),
                    GitWorkflowScenarios::multiCommitSinglePush),
            scenario("complex-file-update", WRITE,
                    complexFileState(), GitWorkflowScenarios::complexFileUpdate),
            scenario("delete-file-and-pull", PULL,
                    deletedFileState(), GitWorkflowScenarios::deleteFileAndPull),
            scenario("rename-file-and-pull", PULL,
                    renamedFileState(), GitWorkflowScenarios::renameFileAndPull),
            scenario("file-directory-replacement-and-pull", PULL,
                    fileDirectoryReplacementState(), GitWorkflowScenarios::fileDirectoryReplacementAndPull),
            scenario("second-branch-fetch-and-checkout", FETCH, branchState(),
                    GitWorkflowScenarios::secondBranchFetchAndCheckout),
            scenario("multi-ref-push", WRITE, multiRefState(), GitWorkflowScenarios::multiRefPush),
            scenario("delete-branch", WRITE, state(Map.of(), Map.of()), GitWorkflowScenarios::deleteBranch),
            scenario("delete-tags", WRITE, twoCommitState("updated\n"), GitWorkflowScenarios::deleteTags),
            scenario("force-push-unrelated-history", WRITE, forcePushState(),
                    GitWorkflowScenarios::forcePushUnrelatedHistory),
            scenario("reject-stale-non-fast-forward", PULL, twoCommitState("winner\n"),
                    GitWorkflowScenarios::rejectStaleNonFastForward),
            scenario("incremental-fetch-with-common-commit", FETCH, twoCommitState("incremental\n"),
                    GitWorkflowScenarios::incrementalFetchWithCommonCommit),
            scenario("merge-history-clone-and-fetch", FETCH, mergedHistoryState(),
                    GitWorkflowScenarios::mergeHistoryCloneAndFetch),
            scenario("annotated-tag-discovery-and-fetch", CLONE, annotatedTagState(),
                    GitWorkflowScenarios::annotatedTagDiscoveryAndFetch),
            scenario("unicode-refs-discovery-fetch-and-push", FETCH,
                    state(Map.of(MAIN, "initial", "refs/heads/ветка", "second", "refs/tags/версия", "tag"),
                            twoCommitState("updated\n").commits()), GitWorkflowScenarios::unicodeRefs));
    private static final GitScenario MISSING_REPOSITORY_FIRST_PUSH = scenario(
            "orion-missing-repository-first-push",
            WRITE,
            Set.of(GitCapability.INITIALIZE, GitCapability.COMMIT, GitCapability.PUSH,
                    GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH),
            initialState(),
            GitScenario.RemoteRepositoryMode.MISSING,
            GitWorkflowScenarios::initialPushWithoutClone);

    private GitWorkflowScenarios() {
    }

    public static List<GitScenario> catalog() {
        return CATALOG;
    }

    public static GitScenario missingRepositoryFirstPush() {
        return MISSING_REPOSITORY_FIRST_PUSH;
    }

    private static void emptyRepositoryDiscovery(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            source.addRemote("origin", context.remote());
            require(source.advertisedRefs("origin").isEmpty(), "empty repository advertises refs");
            execution.assertTerminal(context.server().snapshot(context.remote()));
        }
    }

    private static void initialPushAndClone(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot remote = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                equivalent(remote, clone.snapshot(), "initial clone");
            }
            execution.assertTerminal(remote);
        }
    }

    private static void emptyCloneAndFirstPush(GitScenarioContext context, Execution execution) throws Exception {
        RepositorySnapshot empty = context.server().snapshot(context.remote());
        try (GitWorkTree clone = context.client().clone(
                context.remote(), context.workTreeDirectory("empty-clone"))) {
            equivalent(empty, clone.snapshot(), "empty clone");
            equivalent(empty, context.server().snapshot(context.remote()), "remote after empty clone");
            execution.bind("initial", commit(clone, README, INITIAL_CONTENT, "initial"));
            clone.push("origin", "main");
            RepositorySnapshot remote = transferred(context, clone);
            try (GitWorkTree observer = context.client().clone(
                    context.remote(), context.workTreeDirectory("after-first-push"))) {
                equivalent(remote, observer.snapshot(), "clone after first push from empty clone");
            }
            execution.assertTerminal(remote);
        }
    }

    private static void initialPushWithoutClone(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            execution.assertTerminal(transferred(context, source));
        }
    }

    private static void cloneMultipleCommitHistory(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            execution.bind("second", commit(source, README, "second\n", "second"));
            execution.bind("third", commit(source, README, "third\n", "third"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot remote = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                equivalent(remote, clone.snapshot(), "multi-commit clone");
            }
            execution.assertTerminal(remote);
        }
    }

    private static void fastForwardPushAndPull(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot initialRemote = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                equivalent(initialRemote, clone.snapshot(), "clone before pull");
                execution.bind("second", commit(source, README, "updated\n", "updated"));
                source.push("origin", "main");
                RepositorySnapshot updatedRemote = transferred(context, source);
                clone.pull("origin", "main");
                equivalent(updatedRemote, clone.snapshot(), "fast-forward pull");
                execution.assertTerminal(updatedRemote);
            }
        }
    }

    private static void alternatingTwoClientRoundTrip(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree first = source(context)) {
            execution.bind("initial", commit(first, README, INITIAL_CONTENT, "initial"));
            first.addRemote("origin", context.remote());
            first.push("origin", "main");
            RepositorySnapshot initialRemote = transferred(context, first);
            try (GitWorkTree second = context.client().clone(
                    context.remote(), context.workTreeDirectory("second"))) {
                equivalent(initialRemote, second.snapshot(), "second client clone");
                execution.bind("second", commit(second, README, "second\n", "second client"));
                second.push("origin", "main");
                RepositorySnapshot secondRemote = transferred(context, second);
                first.pull("origin", "main");
                equivalent(secondRemote, first.snapshot(), "first client pull");
                execution.bind("third", commit(first, README, "first-again\n", "first client again"));
                first.push("origin", "main");
                RepositorySnapshot terminal = transferred(context, first);
                second.pull("origin", "main");
                equivalent(terminal, second.snapshot(), "second client pull");
                execution.assertTerminal(terminal);
            }
        }
    }

    private static void multiCommitSinglePush(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            execution.bind("second", commit(source, README, "second\n", "second"));
            execution.bind("third", commit(source, README, "third\n", "third"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            execution.assertTerminal(transferred(context, source));
        }
    }

    private static void complexFileUpdate(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            transferred(context, source);
            source.writeFile("nested/path/value.txt", "nested\n");
            source.writeFile("empty.txt", new byte[0]);
            source.writeFile("binary.dat", new byte[] {0, 1, 2, (byte) 0xff});
            source.writeFile("unicodé/файл.txt", "Grüße 世界\n");
            source.add("nested/path/value.txt", "empty.txt", "binary.dat", "unicodé/файл.txt");
            source.commit("complex files");
            execution.bind("second", source.head());
            source.push("origin", "main");
            execution.assertTerminal(transferred(context, source));
        }
    }

    private static void deleteFileAndPull(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            source.writeFile(README, INITIAL_CONTENT);
            source.writeFile("nested/remove.txt", "remove\n");
            source.add(README, "nested/remove.txt");
            source.commit("initial");
            execution.bind("initial", source.head());
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot initial = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                equivalent(initial, clone.snapshot(), "clone before deletion");
                source.delete("nested/remove.txt");
                source.add("nested/remove.txt");
                source.commit("delete file");
                execution.bind("second", source.head());
                source.push("origin", "main");
                RepositorySnapshot terminal = transferred(context, source);
                clone.pull("origin", "main");
                equivalent(terminal, clone.snapshot(), "pull after deletion");
                execution.assertTerminal(terminal);
            }
        }
    }

    private static void renameFileAndPull(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            source.writeFile("old/name.txt", "moved\n");
            source.writeFile("old/keep.txt", "old neighbor\n");
            source.writeFile("new/keep.txt", "new neighbor\n");
            source.add("old/name.txt", "old/keep.txt", "new/keep.txt");
            source.commit("initial");
            execution.bind("initial", source.head());
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot initial = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                equivalent(initial, clone.snapshot(), "clone before rename");
                Files.move(source.directory().resolve("old/name.txt"), source.directory().resolve("new/name.txt"));
                source.add("old/name.txt", "new/name.txt");
                source.commit("rename file");
                execution.bind("second", source.head());
                source.push("origin", "main");
                RepositorySnapshot terminal = transferred(context, source);
                clone.pull("origin", "main");
                equivalent(terminal, clone.snapshot(), "pull after rename");
                execution.assertTerminal(terminal);
            }
        }
    }

    private static void fileDirectoryReplacementAndPull(GitScenarioContext context, Execution execution)
            throws Exception {
        try (GitWorkTree source = source(context)) {
            source.writeFile("item", "file\n");
            source.writeFile(README, INITIAL_CONTENT);
            source.add("item", README);
            source.commit("initial");
            execution.bind("initial", source.head());
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot initial = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                equivalent(initial, clone.snapshot(), "clone before structural replacement");
                source.delete("item");
                source.add("item");
                source.writeFile("item/child.txt", "child\n");
                source.add("item/child.txt");
                source.commit("file to directory");
                execution.bind("second", source.head());
                source.push("origin", "main");
                RepositorySnapshot directoryState = transferred(context, source);
                clone.pull("origin", "main");
                equivalent(directoryState, clone.snapshot(), "pull after file became directory");

                source.delete("item/child.txt");
                source.add("item/child.txt");
                source.delete("item");
                source.writeFile("item", "replacement\n");
                source.add("item");
                source.commit("directory to file");
                execution.bind("third", source.head());
                source.push("origin", "main");
                RepositorySnapshot terminal = transferred(context, source);
                clone.pull("origin", "main");
                equivalent(terminal, clone.snapshot(), "pull after directory became file");
                execution.assertTerminal(terminal);
            }
        }
    }

    private static void secondBranchFetchAndCheckout(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot initialRemote = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("feature"))) {
                equivalent(initialRemote, clone.snapshot(), "clone before feature push");
                source.writeFile(FEATURE_FILE, "feature\n");
                source.add(FEATURE_FILE);
                source.commit("feature");
                execution.bind("feature", source.head());
                source.updateRef(FEATURE, "HEAD");
                source.updateRef(MAIN, execution.id("initial"));
                source.pushRefs("origin", FEATURE + ":" + FEATURE);
                RepositorySnapshot remote = transferred(context, source);
                clone.fetch("origin", "feature");
                clone.checkout("feature", "refs/remotes/origin/feature");
                require(clone.head().equals(execution.id("feature")), "feature checkout has the wrong tip");
                RepositorySnapshot checkedOut = RepositorySnapshot.of(FEATURE, remote.refs(), remote.commits());
                equivalent(checkedOut, clone.snapshot(), "independent feature checkout");
                execution.assertTerminal(remote);
            }
        }
    }

    private static void multiRefPush(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            transferred(context, source);
            source.updateRef(FEATURE, "HEAD");
            source.updateRef(TAG, "HEAD");
            source.pushRefs("origin", FEATURE + ":" + FEATURE, TAG + ":" + TAG);
            execution.assertTerminal(transferred(context, source));
        }
    }

    private static void deleteBranch(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.updateRef(FEATURE, "HEAD");
            source.addRemote("origin", context.remote());
            source.pushRefs("origin", MAIN + ":" + MAIN, FEATURE + ":" + FEATURE);
            RepositorySnapshot before = transferred(context, source);

            source.pushRefs("origin", ":" + FEATURE);
            equivalent(RepositorySnapshot.of(MAIN, Map.of(MAIN, execution.id("initial")), before.commits()),
                    context.server().snapshot(context.remote()), "delete feature and preserve main");
            require(!source.advertisedRefs("origin").containsKey(FEATURE), "deleted branch is still advertised");

            source.pushRefs("origin", ":" + MAIN);
            execution.assertTerminal(context.server().snapshot(context.remote()));
            require(source.advertisedRefs("origin").isEmpty(), "empty repository still advertises refs");
        }
    }

    private static void deleteTags(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.updateRef(TAG, "HEAD");
            String annotatedRef = "refs/tags/annotated";
            execution.bind("annotated", source.annotatedTag("annotated", execution.id("initial")));
            source.addRemote("origin", context.remote());
            source.pushRefs("origin", MAIN + ":" + MAIN, TAG + ":" + TAG, annotatedRef + ":" + annotatedRef);
            transferred(context, source);

            execution.bind("second", commit(source, README, "updated\n", "updated"));
            source.pushRefs("origin", MAIN + ":" + MAIN, ":" + TAG);
            equivalent(RepositorySnapshot.of(MAIN,
                    Map.of(MAIN, execution.id("second"), annotatedRef, execution.id("annotated")),
                    source.snapshot().commits()), context.server().snapshot(context.remote()),
                    "update main and delete lightweight tag");

            source.pushRefs("origin", ":" + annotatedRef);
            execution.assertTerminal(context.server().snapshot(context.remote()));
            Map<String, String> advertised = source.advertisedRefs("origin");
            require(!advertised.containsKey(TAG) && !advertised.containsKey(annotatedRef)
                    && !advertised.containsKey(annotatedRef + "^{}"), "deleted tag is still advertised");
        }
    }

    private static void forcePushUnrelatedHistory(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = source(context);
                GitWorkTree replacement = context.client().init(context.workTreeDirectory("replacement"))) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.updateRef(FEATURE, "HEAD");
            source.addRemote("origin", context.remote());
            source.pushRefs("origin", MAIN + ":" + MAIN, FEATURE + ":" + FEATURE);
            RepositorySnapshot before = transferred(context, source);

            execution.bind("replacement", commit(replacement, README, "replacement\n", "replacement"));
            replacement.addRemote("origin", context.remote());
            GitOperationResult rejection = context.performAgainstRemote(
                    () -> replacement.pushResult("origin", "main"));
            require(rejection.status() == GitOperationResult.Status.NON_FAST_FORWARD,
                    "unrelated push was not classified as non-fast-forward: " + rejection.status());
            require(rejection.stateUnchanged(), "unrelated push changed the remote repository");
            equivalent(before, rejection.after(), "remote after rejected unrelated push");

            replacement.pushRefs("origin", "+" + MAIN + ":" + MAIN);
            execution.assertTerminal(context.server().snapshot(context.remote()));
            Map<String, String> advertised = replacement.advertisedRefs("origin");
            require(execution.id("replacement").equals(advertised.get(MAIN)), "forced tip is not advertised");
            require(execution.id("initial").equals(advertised.get(FEATURE)), "force push changed another branch");
        }
    }

    private static void rejectStaleNonFastForward(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            transferred(context, source);
            try (GitWorkTree winner = context.client().clone(
                    context.remote(), context.workTreeDirectory("winner"));
                    GitWorkTree stale = context.client().clone(
                            context.remote(), context.workTreeDirectory("stale"))) {
                execution.bind("second", commit(winner, README, "winner\n", "winner"));
                winner.push("origin", "main");
                RepositorySnapshot winning = transferred(context, winner);
                commit(stale, README, "stale\n", "stale");
                GitOperationResult rejection = context.performAgainstRemote(
                        () -> stale.pushResult("origin", "main"));
                require(rejection.status() == GitOperationResult.Status.NON_FAST_FORWARD,
                        "stale push was not classified as non-fast-forward: " + rejection.status());
                require(rejection.stateUnchanged(), "stale push changed the remote repository");
                equivalent(winning, rejection.after(), "winning state after stale rejection");
                execution.assertTerminal(winning);
            }
        }
    }

    private static void mergeHistoryCloneAndFetch(GitScenarioContext context, Execution execution) throws Exception {
        try (GitWorkTree source = GitClients.jgitAllowAllSsh().init(context.workTreeDirectory("source"))) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot initial = transferred(context, source);
            try (GitWorkTree incremental = context.client().clone(
                    context.remote(), context.workTreeDirectory("incremental"));
                 Git seed = Git.open(source.directory().toFile())) {
                equivalent(initial, incremental.snapshot(), "clone before merge history");
                seed.checkout().setCreateBranch(true).setName("feature").call();
                execution.bind("feature", commit(source, FEATURE_FILE, "feature\n", "feature"));
                seed.checkout().setName("main").call();
                execution.bind("main", commit(source, README, "main change\n", "main change"));
                MergeResult merged = seed.merge().include(seed.getRepository().resolve(FEATURE))
                        .setCommit(false).call();
                require(merged.getMergeStatus() == MergeResult.MergeStatus.MERGED_NOT_COMMITTED,
                        "seed history did not produce a merge");
                source.commit("merge feature");
                execution.bind("merge", source.head());
                seed.branchDelete().setBranchNames("feature").call();
                source.push("origin", "main");
                RepositorySnapshot terminal = transferred(context, source);
                execution.assertTerminal(terminal);

                incremental.fetch("origin", "main");
                require(incremental.head().equals(execution.id("initial")), "fetch moved local main");
                incremental.updateRef("refs/heads/fetched", "refs/remotes/origin/main");
                RepositorySnapshot fetched = RepositorySnapshot.of(MAIN, Map.of(
                        MAIN, execution.id("initial"), "refs/heads/fetched", execution.id("merge")),
                        terminal.commits());
                equivalent(fetched, incremental.snapshot(), "incremental fetch of merge history");
                try (GitWorkTree clone = context.client().clone(
                        context.remote(), context.workTreeDirectory("merged-clone"))) {
                    equivalent(terminal, clone.snapshot(), "full clone of merge history");
                }
                equivalent(terminal, context.server().snapshot(context.remote()), "remote after merge fetches");
            }
        }
    }

    private static void incrementalFetchWithCommonCommit(
            GitScenarioContext context,
            Execution execution) throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            RepositorySnapshot initialRemote = transferred(context, source);
            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                equivalent(initialRemote, clone.snapshot(), "clone before incremental fetch");
                execution.bind("second", commit(source, README, "incremental\n", "incremental"));
                source.push("origin", "main");
                RepositorySnapshot terminal = transferred(context, source);
                clone.fetch("origin", "main");
                clone.updateRef("refs/heads/fetched", "refs/remotes/origin/main");
                RepositorySnapshot fetched = RepositorySnapshot.of(
                        MAIN,
                        Map.of(
                                MAIN, execution.id("initial"),
                                "refs/heads/fetched", execution.id("second")),
                        terminal.commits());
                equivalent(fetched, clone.snapshot(), "incremental fetch");
                clone.pull("origin", "main");
                RepositorySnapshot pulled = RepositorySnapshot.of(
                        MAIN,
                        Map.of(
                                MAIN, execution.id("second"),
                                "refs/heads/fetched", execution.id("second")),
                        terminal.commits());
                equivalent(pulled, clone.snapshot(), "pull after incremental fetch");
                execution.assertTerminal(terminal);
            }
        }
    }

    private static void annotatedTagDiscoveryAndFetch(GitScenarioContext context, Execution execution)
            throws Exception {
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            execution.bind("annotated", source.annotatedTag("annotated", execution.id("initial")));
            execution.bind("nested", source.annotatedTag("nested", execution.id("annotated")));
            execution.bind("second", commit(source, README, "updated\n", "second"));
            source.updateRef("refs/tags/lightweight", execution.id("second"));
            source.addRemote("origin", context.remote());
            source.pushRefs("origin", MAIN + ":" + MAIN,
                    "refs/tags/annotated:refs/tags/annotated", "refs/tags/nested:refs/tags/nested",
                    "refs/tags/lightweight:refs/tags/lightweight");
            Map<String, String> refs = source.advertisedRefs("origin");
            require(execution.id("annotated").equals(refs.get("refs/tags/annotated")), "annotated tag ID missing");
            require(execution.id("nested").equals(refs.get("refs/tags/nested")), "nested tag ID missing");
            require(execution.id("initial").equals(refs.get("refs/tags/annotated^{}")), "peeled tag ID missing");
            require(execution.id("initial").equals(refs.get("refs/tags/nested^{}")), "nested tag not fully peeled");
            require(execution.id("second").equals(refs.get("refs/tags/lightweight")), "lightweight tag missing");
            require(!refs.containsKey("refs/tags/lightweight^{}"), "lightweight tag must not have a peeled record");
            RepositorySnapshot remote = transferred(context, source);
            execution.assertTerminal(remote);
            try (GitWorkTree clone = context.client().clone(context.remote(), context.workTreeDirectory("clone"))) {
                RepositorySnapshot actual = clone.snapshot();
                require(execution.id("second").equals(actual.refs().get(MAIN)), "clone main ref differs");
                require(remote.commits().equals(actual.commits()), "clone commit history differs");
            }
            GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
            Path target = context.workTreeDirectory("tag-fetch");
            git.run(null, "init", target.toString());
            git.run(target, "-c", "protocol.version=1", "fetch", "--no-tags", context.remote().uri(),
                    "refs/tags/nested");
            require(git.run(target, "rev-parse", "FETCH_HEAD^{}").trimmed().equals(execution.id("initial")),
                    "fetched nested tag target differs");
            require(git.run(target, "show", "FETCH_HEAD:" + README).output().equals(INITIAL_CONTENT),
                    "fetched tag lost file content");
        }
    }

    private static void unicodeRefs(GitScenarioContext context, Execution execution) throws Exception {
        String branch = "refs/heads/ветка";
        String tag = "refs/tags/версия";
        try (GitWorkTree source = source(context)) {
            execution.bind("initial", commit(source, README, INITIAL_CONTENT, "initial"));
            source.addRemote("origin", context.remote());
            source.push("origin", "main");
            execution.bind("second", commit(source, README, "updated\n", "second"));
            source.updateRef(branch, "HEAD");
            source.updateRef(MAIN, execution.id("initial"));
            execution.bind("tag", source.annotatedTag("версия", execution.id("second")));
            source.pushRefs("origin", branch + ":" + branch, tag + ":" + tag);
            Map<String, String> refs = source.advertisedRefs("origin");
            require(execution.id("initial").equals(refs.get(MAIN)), "ASCII main ref lost");
            require(execution.id("second").equals(refs.get(branch)), "Unicode branch lost");
            require(execution.id("tag").equals(refs.get(tag)), "Unicode tag lost");
            require(execution.id("second").equals(refs.get(tag + "^{}")), "Unicode tag peel lost");
            execution.assertTerminal(transferred(context, source));
            try (GitWorkTree clone = context.client().clone(context.remote(), context.workTreeDirectory("clone"))) {
                clone.fetch("origin", "main");
                require(clone.head().equals(execution.id("initial")), "Unicode refs interfered with main fetch");
                clone.fetch("origin", "ветка");
                clone.checkout("ветка", "refs/remotes/origin/ветка");
                require(clone.head().equals(execution.id("second")), "Unicode branch fetch lost its tip");
            }
            GitCommandRunner git = new GitCommandRunner("git", Duration.ofSeconds(30));
            Path target = context.workTreeDirectory("tag-fetch");
            git.run(null, "init", target.toString());
            git.run(target, "fetch", "--no-tags", context.remote().uri(), tag);
            require(git.run(target, "rev-parse", "FETCH_HEAD^{}").trimmed().equals(execution.id("second")),
                    "Unicode tag fetch lost its target");
        }
    }

    private static GitWorkTree source(GitScenarioContext context) throws Exception {
        return context.client().init(context.workTreeDirectory("source"));
    }

    private static String commit(
            GitWorkTree workTree,
            String path,
            String content,
            String message) throws Exception {
        workTree.writeFile(path, content);
        workTree.add(path);
        workTree.commit(message);
        return workTree.head();
    }

    private static RepositorySnapshot transferred(
            GitScenarioContext context,
            GitWorkTree source) throws Exception {
        RepositorySnapshot remote = context.server().snapshot(context.remote());
        equivalent(source.snapshot(), remote, "pushed repository");
        return remote;
    }

    private static void equivalent(RepositorySnapshot expected, RepositorySnapshot actual, String transfer) {
        String difference = expected.difference(actual);
        require(difference == null, transfer + " differs: " + difference);
    }

    private static GitScenario scenario(
            String name,
            Set<GitCapability> capabilities,
            ExpectedRepositoryState expected,
            Workflow workflow) {
        return new DeclaredScenario(name, capabilities, capabilities, expected, workflow);
    }

    private static GitScenario scenario(
            String name,
            Set<GitCapability> clientCapabilities,
            Set<GitCapability> serverCapabilities,
            ExpectedRepositoryState expected,
            GitScenario.RemoteRepositoryMode remoteRepositoryMode,
            Workflow workflow) {
        return new DeclaredScenario(
                name, clientCapabilities, serverCapabilities, expected, remoteRepositoryMode, workflow);
    }

    private static ExpectedRepositoryState initialState() {
        return state(Map.of(MAIN, "initial"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT)))));
    }

    private static ExpectedRepositoryState twoCommitState(String content) {
        return state(Map.of(MAIN, "second"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT))),
                "second", expectedCommit(List.of("initial"), Map.of(README, text(content)))));
    }

    private static ExpectedRepositoryState threeCommitState(String content) {
        return state(Map.of(MAIN, "third"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT))),
                "second", expectedCommit(List.of("initial"), Map.of(README, text("second\n"))),
                "third", expectedCommit(List.of("second"), Map.of(README, text(content)))));
    }

    private static ExpectedRepositoryState deletedFileState() {
        return state(Map.of(MAIN, "second"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(
                        README, text(INITIAL_CONTENT), "nested/remove.txt", text("remove\n"))),
                "second", expectedCommit(List.of("initial"), Map.of(README, text(INITIAL_CONTENT)))));
    }

    private static ExpectedRepositoryState renamedFileState() {
        return state(Map.of(MAIN, "second"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(
                        "old/name.txt", text("moved\n"),
                        "old/keep.txt", text("old neighbor\n"), "new/keep.txt", text("new neighbor\n"))),
                "second", expectedCommit(List.of("initial"), Map.of(
                        "new/name.txt", text("moved\n"),
                        "old/keep.txt", text("old neighbor\n"), "new/keep.txt", text("new neighbor\n")))));
    }

    private static ExpectedRepositoryState fileDirectoryReplacementState() {
        return state(Map.of(MAIN, "third"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT), "item", text("file\n"))),
                "second", expectedCommit(List.of("initial"), Map.of(
                        README, text(INITIAL_CONTENT), "item/child.txt", text("child\n"))),
                "third", expectedCommit(List.of("second"), Map.of(
                        README, text(INITIAL_CONTENT), "item", text("replacement\n")))));
    }

    private static ExpectedRepositoryState mergedHistoryState() {
        return state(Map.of(MAIN, "merge"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT))),
                "feature", expectedCommit(List.of("initial"), Map.of(
                        README, text(INITIAL_CONTENT), FEATURE_FILE, text("feature\n"))),
                "main", expectedCommit(List.of("initial"), Map.of(README, text("main change\n"))),
                "merge", expectedCommit(List.of("main", "feature"), Map.of(
                        README, text("main change\n"), FEATURE_FILE, text("feature\n")))));
    }

    private static ExpectedRepositoryState complexFileState() {
        Map<String, ExpectedRepositoryState.ExpectedFile> files = new LinkedHashMap<>();
        files.put(README, text(INITIAL_CONTENT));
        files.put("nested/path/value.txt", text("nested\n"));
        files.put("empty.txt", bytes(new byte[0]));
        files.put("binary.dat", bytes(new byte[] {0, 1, 2, (byte) 0xff}));
        files.put("unicodé/файл.txt", text("Grüße 世界\n"));
        return state(Map.of(MAIN, "second"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT))),
                "second", expectedCommit(List.of("initial"), files)));
    }

    private static ExpectedRepositoryState branchState() {
        return state(Map.of(MAIN, "initial", FEATURE, "feature"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT))),
                "feature", expectedCommit(List.of("initial"), Map.of(
                        README, text(INITIAL_CONTENT),
                        FEATURE_FILE, text("feature\n")))));
    }

    private static ExpectedRepositoryState annotatedTagState() {
        return state(Map.of(MAIN, "second", "refs/tags/lightweight", "second",
                "refs/tags/annotated", "annotated", "refs/tags/nested", "nested"),
                twoCommitState("updated\n").commits());
    }

    private static ExpectedRepositoryState forcePushState() {
        return state(Map.of(MAIN, "replacement", FEATURE, "initial"), Map.of(
                "initial", expectedCommit(List.of(), Map.of(README, text(INITIAL_CONTENT))),
                "replacement", expectedCommit(List.of(), Map.of(README, text("replacement\n")))));
    }

    private static ExpectedRepositoryState multiRefState() {
        return state(Map.of(MAIN, "initial", FEATURE, "initial", TAG, "initial"), initialState().commits());
    }

    private static ExpectedRepositoryState state(
            Map<String, String> refs,
            Map<String, ExpectedRepositoryState.ExpectedCommit> commits) {
        return new ExpectedRepositoryState(MAIN, refs, commits);
    }

    private static ExpectedRepositoryState.ExpectedCommit expectedCommit(
            List<String> parents,
            Map<String, ExpectedRepositoryState.ExpectedFile> files) {
        return new ExpectedRepositoryState.ExpectedCommit(parents, files);
    }

    private static ExpectedRepositoryState.ExpectedFile text(String content) {
        return bytes(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ExpectedRepositoryState.ExpectedFile bytes(byte[] content) {
        return new ExpectedRepositoryState.ExpectedFile(0100644, sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Workflow {
        void run(GitScenarioContext context, Execution execution) throws Exception;
    }

    private record DeclaredScenario(
            String name,
            Set<GitCapability> requiredClientCapabilities,
            Set<GitCapability> requiredServerCapabilities,
            ExpectedRepositoryState expectedTerminalState,
            GitScenario.RemoteRepositoryMode remoteRepositoryMode,
            Workflow workflow) implements GitScenario {
        private DeclaredScenario(
                String name,
                Set<GitCapability> requiredClientCapabilities,
                Set<GitCapability> requiredServerCapabilities,
                ExpectedRepositoryState expectedTerminalState,
                Workflow workflow) {
            this(name, requiredClientCapabilities, requiredServerCapabilities, expectedTerminalState,
                    GitScenario.RemoteRepositoryMode.PROVISIONED, workflow);
        }

        private DeclaredScenario {
            requiredClientCapabilities = Set.copyOf(requiredClientCapabilities);
            requiredServerCapabilities = Set.copyOf(requiredServerCapabilities);
        }

        @Override
        public Set<GitCapability> requiredCapabilities() {
            java.util.HashSet<GitCapability> capabilities = new java.util.HashSet<>(requiredClientCapabilities);
            capabilities.addAll(requiredServerCapabilities);
            return Set.copyOf(capabilities);
        }

        @Override
        public void run(GitScenarioContext context) throws Exception {
            workflow.run(context, new Execution(expectedTerminalState));
        }
    }

    private static final class Execution {
        private final ExpectedRepositoryState expected;
        private final Map<String, String> ids = new LinkedHashMap<>();

        private Execution(ExpectedRepositoryState expected) {
            this.expected = expected;
        }

        private void bind(String label, String objectId) {
            String previous = ids.putIfAbsent(label, objectId);
            require(previous == null || previous.equals(objectId), "commit label changed: " + label);
        }

        private String id(String label) {
            String objectId = ids.get(label);
            require(objectId != null, "commit label is unbound: " + label);
            return objectId;
        }

        private void assertTerminal(RepositorySnapshot actual) {
            require(actual.headSymref().equals(expected.headSymref()),
                    "terminal HEAD expected=" + expected.headSymref() + " actual=" + actual.headSymref());
            require(actual.refs().size() == expected.refs().size(),
                    "terminal refs expected=" + expected.refs() + " actual=" + actual.refs());
            for (Map.Entry<String, String> ref : expected.refs().entrySet()) {
                require(id(ref.getValue()).equals(actual.refs().get(ref.getKey())),
                        "terminal ref has wrong tip: " + ref.getKey());
            }
            require(actual.commits().size() == expected.commits().size(),
                    "terminal commit count expected=" + expected.commits().size()
                            + " actual=" + actual.commits().size());
            for (Map.Entry<String, ExpectedRepositoryState.ExpectedCommit> entry
                    : expected.commits().entrySet()) {
                assertCommit(entry.getKey(), entry.getValue(), actual.commits().get(id(entry.getKey())));
            }
        }

        private void assertCommit(
                String label,
                ExpectedRepositoryState.ExpectedCommit expectedCommit,
                RepositorySnapshot.Commit actual) {
            require(actual != null, "terminal commit is missing: " + label);
            List<String> parents = expectedCommit.parents().stream().map(this::id).toList();
            require(actual.parents().equals(parents),
                    "terminal ancestry expected=" + parents + " actual=" + actual.parents());
            require(actual.entries().keySet().equals(expectedCommit.files().keySet()),
                    "terminal tree paths expected=" + expectedCommit.files().keySet()
                            + " actual=" + actual.entries().keySet());
            for (Map.Entry<String, ExpectedRepositoryState.ExpectedFile> file
                    : expectedCommit.files().entrySet()) {
                RepositorySnapshot.TreeEntry actualFile = actual.entries().get(file.getKey());
                require(actualFile.mode() == file.getValue().mode(), "wrong mode for " + file.getKey());
                require(actualFile.contentHash().equals(file.getValue().contentHash()),
                        "wrong content for " + file.getKey());
                require(actualFile.objectId().length() == 40, "wrong blob id for " + file.getKey());
            }
            require(actual.tree().length() == 40, "wrong tree id for " + label);
        }
    }
}
