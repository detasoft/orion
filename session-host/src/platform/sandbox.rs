use crate::cli::SessionOptions;
use crate::host::HostError;
use crate::sandbox::{self, CompiledPolicy};

pub(super) struct PreparedSandbox {
    pub(super) policy: Option<CompiledPolicy>,
    #[cfg(target_os = "linux")]
    ruleset: Option<landlock::RulesetCreated>,
}

impl PreparedSandbox {
    pub(super) fn prepare(options: &SessionOptions) -> Result<Self, HostError> {
        let Some(path) = &options.sandbox_policy else {
            return Ok(Self {
                policy: None,
                #[cfg(target_os = "linux")]
                ruleset: None,
            });
        };
        let policy = sandbox::load(path)?;
        #[cfg(target_os = "linux")]
        {
            let rules = open_rules(&policy).map_err(HostError::Policy)?;
            match classify_ruleset_result(prepare_linux(rules)) {
                Ok(Preparation::Enforced(ruleset)) => Ok(Self {
                    policy: Some(policy),
                    ruleset: Some(ruleset),
                }),
                Ok(Preparation::Unenforced { detail }) => {
                    eprintln!(
                        "session-host: warning: Landlock ABI 9 is unavailable; \
                         running without filesystem restrictions: {}",
                        detail
                    );
                    Ok(Self {
                        policy: Some(policy),
                        ruleset: None,
                    })
                }
                Err(error) => Err(HostError::Policy(error.to_string())),
            }
        }
        #[cfg(not(target_os = "linux"))]
        {
            validate_rules(&policy)?;
            eprintln!(
                "session-host: warning: Landlock ABI 9 is unavailable; \
                 running without filesystem restrictions: Landlock is unavailable on this platform"
            );
            Ok(Self { policy: Some(policy) })
        }
    }

    pub(super) fn enforced(&self) -> bool {
        #[cfg(target_os = "linux")]
        {
            self.ruleset.is_some()
        }
        #[cfg(not(target_os = "linux"))]
        {
            false
        }
    }

    pub(super) fn requested(&self) -> bool {
        self.policy.is_some()
    }

    pub(super) fn policy(&self) -> Option<&CompiledPolicy> {
        self.policy.as_ref()
    }

    pub(super) fn restrict_child(self) -> Result<(), ()> {
        #[cfg(target_os = "linux")]
        if let Some(ruleset) = self.ruleset {
            use landlock::{CompatLevel, Compatible, RulesetStatus};
            let status = ruleset
                .set_compatibility(CompatLevel::HardRequirement)
                .restrict_self()
                .map(|status| {
                    (
                        status.ruleset == RulesetStatus::FullyEnforced,
                        status.no_new_privs,
                    )
                });
            finish_restriction(status)?;
        }
        Ok(())
    }
}

const DIRECTORY_ONLY_RIGHTS: u64 = ((1 << 14) - 1) & !0b111;

#[cfg(target_os = "linux")]
struct OpenedRule {
    file: std::fs::File,
    rights: u64,
}

#[cfg(target_os = "linux")]
enum Preparation<T> {
    Enforced(T),
    Unenforced { detail: String },
}

#[cfg(target_os = "linux")]
fn classify_ruleset_result<T>(
    result: Result<T, landlock::RulesetError>,
) -> Result<Preparation<T>, landlock::RulesetError> {
    use landlock::{AccessError, CompatError, HandleAccessError, HandleAccessesError, RulesetError};

    match result {
        Ok(value) => Ok(Preparation::Enforced(value)),
        Err(
            error @ RulesetError::HandleAccesses(HandleAccessesError::Fs(
                HandleAccessError::Compat(CompatError::Access(
                    AccessError::Incompatible { .. } | AccessError::PartiallyCompatible { .. },
                )),
            )),
        ) => Ok(Preparation::Unenforced {
            detail: error.to_string(),
        }),
        Err(error) => Err(error),
    }
}

#[cfg(any(target_os = "linux", test))]
fn finish_restriction<E>(result: Result<(bool, bool), E>) -> Result<(), ()> {
    match result {
        Ok((true, true)) => Ok(()),
        Ok(_) | Err(_) => Err(()),
    }
}

#[cfg(target_os = "linux")]
fn prepare_linux(
    rules: Vec<OpenedRule>,
) -> Result<landlock::RulesetCreated, landlock::RulesetError> {
    use landlock::{
        ABI, Access, AccessFs, CompatLevel, Compatible, PathBeneath, Ruleset, RulesetAttr,
        RulesetCreatedAttr,
    };
    let builder = Ruleset::default()
        .set_compatibility(CompatLevel::HardRequirement)
        .handle_access(AccessFs::from_all(ABI::V9))?;
    let mut ruleset = builder.create()?;
    for rule in rules {
        ruleset = ruleset.add_rule(PathBeneath::new(rule.file, access(rule.rights)))?;
    }
    Ok(ruleset)
}

#[cfg(target_os = "linux")]
fn open_rules(policy: &CompiledPolicy) -> Result<Vec<OpenedRule>, String> {
    use std::os::unix::fs::OpenOptionsExt;

    let mut opened = Vec::with_capacity(policy.rules.len());
    for rule in &policy.rules {
        let file = std::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_PATH | libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(&rule.path)
            .map_err(|error| invalid_rule_detail(&rule.path, &error.to_string()))?;
        let metadata = file
            .metadata()
            .map_err(|error| invalid_rule_detail(&rule.path, &error.to_string()))?;
        validate_rule_type(rule.rights, &metadata)
            .map_err(|detail| invalid_rule_detail(&rule.path, &detail))?;
        opened.push(OpenedRule {
            file,
            rights: rule.rights,
        });
    }
    Ok(opened)
}

#[cfg(not(target_os = "linux"))]
fn validate_rules(policy: &CompiledPolicy) -> Result<(), HostError> {
    for rule in &policy.rules {
        let metadata = std::fs::symlink_metadata(&rule.path)
            .map_err(|error| HostError::Policy(invalid_rule_detail(&rule.path, &error.to_string())))?;
        validate_rule_type(rule.rights, &metadata)
            .map_err(|detail| HostError::Policy(invalid_rule_detail(&rule.path, &detail)))?;
    }
    Ok(())
}

fn validate_rule_type(rights: u64, metadata: &std::fs::Metadata) -> Result<(), String> {
    if metadata.file_type().is_symlink() {
        return Err("path is a symbolic link".to_owned());
    }
    if rights & DIRECTORY_ONLY_RIGHTS != 0 && !metadata.is_dir() {
        return Err("directory-only rights require a directory".to_owned());
    }
    Ok(())
}

fn invalid_rule_detail(path: &std::path::Path, detail: &str) -> String {
    format!("invalid Landlock grant path {}: {detail}", path.display())
}

#[cfg(any(target_os = "linux", test))]
fn selected_access<T: Copy>(mask: u64, values: [T; 17]) -> impl Iterator<Item = T> {
    values
        .into_iter()
        .enumerate()
        .filter_map(move |(index, value)| (mask & (1 << index) != 0).then_some(value))
}

#[cfg(target_os = "linux")]
fn access(mask: u64) -> landlock::BitFlags<landlock::AccessFs> {
    use landlock::AccessFs;
    let values = [
        AccessFs::Execute, AccessFs::WriteFile, AccessFs::ReadFile, AccessFs::ReadDir,
        AccessFs::RemoveDir, AccessFs::RemoveFile, AccessFs::MakeChar, AccessFs::MakeDir,
        AccessFs::MakeReg, AccessFs::MakeSock, AccessFs::MakeFifo, AccessFs::MakeBlock,
        AccessFs::MakeSym, AccessFs::Refer, AccessFs::Truncate, AccessFs::IoctlDev,
        AccessFs::ResolveUnix,
    ];
    let mut result = landlock::BitFlags::EMPTY;
    for value in selected_access(mask, values) {
        result |= value;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::{finish_restriction, selected_access};

    #[cfg(target_os = "linux")]
    use super::{Preparation, classify_ruleset_result};

    #[cfg(target_os = "linux")]
    #[test]
    fn only_filesystem_abi_incompatibility_selects_unenforced_preparation() {
        use landlock::{
            AccessError, AccessFs, AddRuleError, AddRulesError, BitFlags, CompatError,
            CreateRulesetError, HandleAccessError, HandleAccessesError, RulesetError,
        };

        fn filesystem_handle_access(error: AccessError<AccessFs>) -> RulesetError {
            RulesetError::HandleAccesses(HandleAccessesError::Fs(HandleAccessError::Compat(
                CompatError::Access(error),
            )))
        }

        let empty = BitFlags::<AccessFs>::EMPTY;
        for error in [
            AccessError::Incompatible { access: empty },
            AccessError::PartiallyCompatible {
                access: empty,
                incompatible: empty,
            },
        ] {
            let compatibility =
                classify_ruleset_result::<()>(Err(filesystem_handle_access(error))).unwrap();
            assert!(matches!(compatibility, Preparation::Unenforced { .. }));
        }

        for error in [
            AccessError::Empty,
            AccessError::Unknown {
                access: empty,
                unknown: empty,
            },
        ] {
            assert!(
                classify_ruleset_result::<()>(Err(filesystem_handle_access(error))).is_err()
            );
        }

        for error in [
            RulesetError::CreateRuleset(CreateRulesetError::MissingHandledAccess),
            RulesetError::AddRules(AddRulesError::Fs(AddRuleError::Compat(
                CompatError::Access(AccessError::Empty),
            ))),
        ] {
            assert!(classify_ruleset_result::<()>(Err(error)).is_err());
        }
    }

    #[test]
    fn restriction_failure_and_incomplete_enforcement_remain_fatal() {
        assert!(finish_restriction::<()>(Err(())).is_err());
        assert!(finish_restriction::<()>(Ok((false, true))).is_err());
        assert!(finish_restriction::<()>(Ok((true, false))).is_err());
        assert_eq!(finish_restriction::<()>(Ok((true, true))), Ok(()));
    }

    #[test]
    fn maps_every_abi_nine_filesystem_right_in_uapi_order() {
        let values = std::array::from_fn::<_, 17, _>(|index| index);
        for bit in 0..17 {
            assert_eq!(
                selected_access(1 << bit, values).collect::<Vec<_>>(),
                vec![bit]
            );
        }
        assert_eq!(
            selected_access((1 << 17) - 1, values).collect::<Vec<_>>(),
            (0..17).collect::<Vec<_>>()
        );
    }
}
