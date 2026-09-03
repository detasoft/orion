package pro.deta.orion.git.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
            scenario("initial-push-and-clone", CLONE,
                    initialState(), GitWorkflowScenarios::initialPushAndClone),
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
            scenario("second-branch-fetch-and-checkout", FETCH, branchState(),
                    GitWorkflowScenarios::secondBranchFetchAndCheckout),
            scenario("multi-ref-push", WRITE, multiRefState(), GitWorkflowScenarios::multiRefPush),
            scenario("reject-stale-non-fast-forward", PULL, twoCommitState("winner\n"),
                    GitWorkflowScenarios::rejectStaleNonFastForward),
            scenario("incremental-fetch-with-common-commit", FETCH, twoCommitState("incremental\n"),
                    GitWorkflowScenarios::incrementalFetchWithCommonCommit));
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
