# Linux Landlock Sandbox Design

## Goal

Let a user describe the filesystem view they want with ordered allow and deny
rules, while enforcing the result with Landlock's allow-only kernel API. The
policy applies to the hosted child and every descendant. The session host
itself remains outside the sandbox so it can manage the control endpoint,
journal, and metadata.

The first implementation uses Landlock only. Mount namespaces, seccomp,
cgroups, network restrictions, and resource limits remain separate future
policy providers.

## Component Boundary

```text
user policy file       AgentD                         session-host
----------------       ---------------------------    -----------------------
ordered DSL       -->  parse and resolve precedence
                       inspect a filesystem snapshot
                       expand deny regions into
                       positive path grants
                       write canonical CBOR        --> validate compiled policy
                                                       build Landlock ruleset
                                                       fork PTY child
                                                       no_new_privs + restrict
                                                       exec command
```

AgentD owns the user-facing language and its filesystem-dependent
preprocessing. `session-host` deliberately does not interpret deny rules or
precedence. It consumes an already positive, Linux-specific policy so the
native boundary stays small and deterministic.

`SessionSpec.Sandbox.policy` continues to identify a file. Before launch it is
the source DSL file. AgentD writes the compiled policy inside the new session
directory and passes that generated path through the existing
`--sandbox-policy` argument. Long policies therefore never become command-line
arguments.

## Source Policy Language

A UTF-8 policy starts with a version header and contains one rule per line:

```text
landlock 1

rox "/bin"
rox "/usr"
ro "/etc/ld.so.cache"
ro "/"
none "/home/user/.ssh"
[rw, read-dir, make-reg, make-dir, make-sym, remove-file, remove-dir] "/workspace"
```

Blank lines and comments beginning with `#` are ignored outside quoted paths.
Paths are double-quoted. Version 1 accepts `\\` and `\"` escapes; other
escapes, NULs, and newlines in a path are rejected. Paths must be absolute and
lexically normalized. In particular, `.` and `..` components are not accepted.

A permission expression is either one token or a comma-separated bracketed
list. The presets are:

| Preset | Rights |
| --- | --- |
| `none` | no rights |
| `ro` | `read-file` |
| `rw` | `read-file`, `write-file`, `truncate` |
| `rox` | `ro`, `execute` |
| `rwx` | `rw`, `execute` |

The individual v1 rights mirror Landlock filesystem access rights through
Landlock ABI 9:

- `execute`, `read-file`, `write-file`, `truncate`, `ioctl-dev`, and
  `resolve-unix` for connecting to a pathname Unix socket;
- `read-dir`, `remove-dir`, and `remove-file`;
- `make-char`, `make-dir`, `make-reg`, `make-sock`, `make-fifo`,
  `make-block`, and `make-sym`;
- `refer` for reparenting objects between directories.

Presets may appear in a bracketed expression, so `[rw, read-dir]` is valid.
`none` is valid only by itself. Unknown tokens, empty lists, and duplicate
tokens are errors. The narrow `rw` preset intentionally permits modification
of existing files but not creation, deletion, directory listing, or execution;
those capabilities must be visible in the policy.

The implicit policy is `none` everywhere. A rule replaces the complete rights
set inherited at its path; it does not add to the previous rule.

Resolution has two simple rules:

1. A rule on a deeper path wins over every ancestor rule, regardless of list
   order.
2. For the same normalized path, the last rule in the file wins.

Thus this policy expresses a real denylist even though Landlock itself only
accepts grants:

```text
landlock 1
ro "/"
none "/home/user/.ssh"
```

## Compiling Denies to Landlock Grants

Landlock unions all rules that cover an object. It cannot subtract a right at
a descendant after that right was granted on an ancestor. AgentD therefore
compiles each right independently over a snapshot of the current filesystem.

For an allowed region with no descendant that removes the right, AgentD emits
one grant at the region root. When a descendant removes the right, AgentD does
not grant the ancestor. It lists the ancestor's current entries, grants the
unaffected siblings, and recursively follows only branches that lead to an
override. After all rights are compiled, grants for the same path are
coalesced into one bit mask.

For example, given:

```text
ro "/"
none "/home"
```

the `read-file` grant on `/` is replaced with grants for current entries such
as `/bin`, `/etc`, and `/usr`, excluding `/home`. For a deny at
`/home/user/.ssh`, the compiler similarly expands siblings at `/`, `/home`,
and `/home/user`.

This is explicitly snapshot behavior. A new sibling created later at a split
boundary receives no grant and is denied. That fail-closed result is preferable
to silently widening the requested policy. Existing sessions are not
recompiled when the filesystem changes.

AgentD does not follow symbolic links while walking split boundaries and does
not emit grants for the links themselves. Access through a link is governed by
the policy at its target's real filesystem location. A source rule whose path
contains a symbolic-link component is rejected because Landlock cannot give a
textual alias independent rights. A user can name the resolved target
explicitly.

Positive grants must identify existing files or directories. A missing deny
path is valid: expansion stops at its first missing component, and both that
future branch and other future siblings remain denied. A missing positive path
is an error because Landlock cannot attach a rule to an object that does not
exist.

### Exactness Limits

Some Landlock rights apply to a directory itself or to operations performed in
that directory. They cannot be preserved on an ancestor while being removed
from a descendant, because the required ancestor grant would also cover that
descendant. Version 1 rejects such policies instead of overgranting or silently
changing their meaning.

The affected rights are `read-dir`, `remove-dir`, `remove-file`, every
`make-*` right, and `refer`. For example, `read-dir "/"` followed by
`none "/home"` is not exactly representable: allowing a listing of `/`
requires a rule rooted at `/`, which would also allow listings below `/home`.
The user may place `read-dir` only on narrower subtrees that do not contain a
restrictive descendant.

Object rights (`read-file`, `write-file`, `execute`, `truncate`, `ioctl-dev`,
and `resolve-unix`) can be split across existing children. If exact compilation
is impossible, AgentD reports a policy error before launching any process. The
`run-unsandboxed` setting never converts a parse, validation, snapshot, or
exactness error into an unsandboxed launch.

To bound resource use, version 1 limits the source size, rule count, path
length, traversal depth, and emitted grant count. Crossing a limit is a policy
error with the source rule and path that caused expansion.

## Compiled CBOR Contract

AgentD writes `sandbox-policy.cbor` atomically in the session directory with
owner-only permissions. The top-level value is a fixed-length array:

```text
[
  1,                         # compiled-policy version
  131071,                    # handled-rights mask
  [
    ["/bin", 5],             # positive path and non-zero rights mask
    ["/usr", 5],
    ["/workspace", 20926]
  ]
]
```

The right bits are the stable Landlock UAPI bit positions, from `execute` at
bit 0 through `resolve-unix` at bit 16. Version 1 handles all seventeen
filesystem rights through Landlock ABI 9, including rights that receive no
grant. This is what makes `none` mean denial rather than absence of
configuration.

The encoding uses definite-length canonical CBOR arrays, unsigned integers,
and UTF-8 text. Rules are sorted by raw UTF-8 path bytes, contain absolute
normalized paths, have non-zero masks, and contain no duplicate paths. The
decoder rejects unknown versions, non-canonical structure, unknown right bits,
zero grants, duplicate or unsorted paths, relative paths, trailing data, and
configured size limits.

The compiled file is not a general Agent protocol message. It is a private,
versioned handoff between AgentD and the colocated native host. A shared binary
fixture locks Java encoding to Rust decoding.

## Applying the Ruleset

On Linux, `session-host` reads and validates the compiled file before the PTY
fork. It asks the running kernel for its Landlock ABI and requires support for
every handled right in the compiled policy. It then creates one ruleset and
adds every positive path-beneath rule while the host is still unrestricted.
Rule path descriptors are opened with `O_PATH | O_CLOEXEC` and retained only
as long as ruleset construction needs them.

The host passes the prepared ruleset into PTY child setup. After the parent has
finished the existing descendant-tracking preparation and releases the child,
the child sets `PR_SET_NO_NEW_PRIVS`, restricts itself with the ruleset, closes
the inherited ruleset descriptor, and only then calls `exec`. All descendants
inherit the restriction. The parent never calls `restrict_self`, so journal,
metadata, and control access remain unaffected.

The child reports setup failure through the pre-exec handshake. AgentD must not
receive a successful launch handoff until sandbox application and `exec` have
both succeeded far enough to establish the existing host contract.

`--sandbox-unavailable=fail` remains the default. A valid policy may run
unsandboxed only when the user explicitly chose `run-unsandboxed` and the
platform, kernel, or active LSM cannot provide the required Landlock ABI.
Malformed compiled CBOR, invalid paths, failed rule construction, and failed
child restriction are policy or launch failures, never availability fallback.

Non-Linux builds continue to compile. With a requested policy they either fail
as unavailable or use the explicit unsandboxed fallback; they do not claim
Landlock enforcement. Windows process-host support remains outside this task.

## Metadata and Diagnostics

Session metadata records whether a policy was requested, whether enforcement
is `landlock` or `none`, the unavailable-policy choice, the compiled policy
version and handled-rights mask, and the effective positive rules with
symbolic right names. It does not copy the source policy text. This description
is diagnostic, non-secret, deterministic, and sufficient to explain the
actual ruleset applied to the child.

AgentD parse errors include line and column. Preprocessing errors identify the
source rule and boundary path. Native errors distinguish unsupported Landlock,
invalid compiled policy, path-open failure, rule-add failure, and child
restriction failure without exposing unrelated environment variables or
command data.

## Testing

AgentD unit tests cover the grammar, presets, comments and quoting, duplicate
paths, deeper-path and last-rule precedence, per-right splitting, sibling
expansion, missing deny paths, symbolic-link rejection, exactness failures,
deterministic ordering, limits, and canonical CBOR output. NativeRuntime tests
verify that it writes the generated file in the session directory, passes only
that path to the host, and cleans up on preprocessing failure.

Rust tests decode the shared Java fixture and reject malformed or non-canonical
inputs. Linux-only tests create temporary allowed and denied trees and verify
read, write, execution, listing, creation, deletion, and descendant
inheritance. They also verify fail-closed behavior when the required Landlock
ABI is unavailable, explicit fallback behavior, and continued host access to
its session files. Portable Unix tests keep the non-Linux unavailable behavior
covered without asserting Windows support.
