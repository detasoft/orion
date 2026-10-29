# Git over SSH: результаты E2E

Команда и среда описаны в основном отчёте.

| Тест | Результат | Время, s |
| --- | --- | --- |
| interactivePtyEditsCompletesAndNeverStartsAnOperatingSystemShell | PASS | 3.72 |
| regularUserCannotListRepositoriesOverSsh | PASS | 2.901 |
| managedUserCanPushAndCloneRepositoryAfterServerRestart | PASS | 7.477 |
| directConfigurationPushRetainsValidStateUntilAValidCommitArrives | FAIL | 3.288 |
| authorizedUserCanCreateRepositoryPushCommitAndFetchItOverSsh | PASS | 4.508 |
| componentCanRestartAndServeSshGitOperationsInSameJvm | PASS | 5.798 |
| managedUserCanCreateOwnRepositoryByFirstPushOverSsh | PASS | 5.777 |
| unknownSshExecCommandReturnsStableFailure | PASS | 2.261 |
| rootSeesSameLifecycleStateOverSshAndHttp | PASS | 3.424 |
| shutdownCommandStopsServerPromptlyOverSsh | PASS | 2.006 |
| authorizedUserCanPushCloneAndPullRepositoryOverSsh | PASS | 3.881 |
| rootCanListRepositoriesOverSsh | PASS | 2.195 |
| rootCanIssueBearerTokenOverSshWithServerIdentityKey | PASS | 2.991 |
| unknownSshKeyCannotCreateRepositoryOverSsh | PASS | 1.939 |
| initialRootPasswordOnlyEnrollsOneKeyAndRemainsConsumedAfterRestart | PASS | 3.109 |
| authenticatedUserCanManageOwnSshCredentials | PASS | 7.124 |
| sshAdminRoutesRemainAccessibleWhenTransportLifecycleIsRunning | PASS | 3.552 |
| forcedLastRootKeyRemovalRequiresResetRecovery | PASS | 5.522 |
| recoveredRootRequiresDedicatedOneTimeEnrollmentAndNewKeyConnection | PASS | 6.415 |
| authenticatedReadOnlyUserCanCloneButCannotPushOverSsh | PASS | 2.702 |
| preseededAclSshScenarioCanRunTwiceInSameJvm | PASS | 4.405 |
| namedUserCanQueryReadOnlyDomainCommandsOverExecAndShell | PASS | 3.822 |
| managedUserUpdateReplacesSshKeyAndRepositoryGrants | PASS | 3.843 |

```text
-------------------------------------------------------------------------------
Test set: pro.deta.orion.test.GitSshTransportEndToEndIT
-------------------------------------------------------------------------------
Tests run: 23, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 92.98 s <<< FAILURE! -- in pro.deta.orion.test.GitSshTransportEndToEndIT
pro.deta.orion.test.GitSshTransportEndToEndIT.directConfigurationPushRetainsValidStateUntilAValidCommitArrives -- Time elapsed: 3.288 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

expected: "invalid"
 but was: "unavailable"
	at pro.deta.orion.test.GitSshTransportEndToEndIT.directConfigurationPushRetainsValidStateUntilAValidCommitArrives(GitSshTransportEndToEndIT.java:186)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)


```
