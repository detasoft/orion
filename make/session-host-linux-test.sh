#!/bin/sh
set -eu

linux_host=${SESSION_HOST_LINUX_HOST:?SESSION_HOST_LINUX_HOST is required}
linux_port=${SESSION_HOST_LINUX_PORT:?SESSION_HOST_LINUX_PORT is required}
remote_root=${SESSION_HOST_LINUX_REMOTE_ROOT:?SESSION_HOST_LINUX_REMOTE_ROOT is required}
linux_cc=${SESSION_HOST_LINUX_CC:?SESSION_HOST_LINUX_CC is required}
linux_ar=${SESSION_HOST_LINUX_AR:?SESSION_HOST_LINUX_AR is required}
linux_cflags=${SESSION_HOST_LINUX_CFLAGS-}
linux_toolchain=${SESSION_HOST_LINUX_TOOLCHAIN_BIN:?SESSION_HOST_LINUX_TOOLCHAIN_BIN is required}
ssh_command=${SESSION_HOST_LINUX_SSH:-ssh}
scp_command=${SESSION_HOST_LINUX_SCP:-scp}

case "$linux_host" in
    *[!A-Za-z0-9_.@:-]* | "")
        printf '%s\n' "unsafe SESSION_HOST_LINUX_HOST: $linux_host" >&2
        exit 64
        ;;
esac
case "$linux_port" in
    *[!0-9]* | "")
        printf '%s\n' "invalid SESSION_HOST_LINUX_PORT: $linux_port" >&2
        exit 64
        ;;
esac
case "$remote_root" in
    / | /*[!A-Za-z0-9_./-]* | "")
        printf '%s\n' "unsafe SESSION_HOST_LINUX_REMOTE_ROOT: $remote_root" >&2
        exit 64
        ;;
    /*) ;;
    *)
        printf '%s\n' "SESSION_HOST_LINUX_REMOTE_ROOT must be absolute" >&2
        exit 64
        ;;
esac
case "/$remote_root/" in
    */./* | */../*)
        printf '%s\n' "unsafe SESSION_HOST_LINUX_REMOTE_ROOT: $remote_root" >&2
        exit 64
        ;;
esac
for remote_tool in "$linux_cc" "$linux_ar" "$linux_toolchain"; do
    case "$remote_tool" in
        /*[!A-Za-z0-9_./-]* | "")
            printf '%s\n' "unsafe remote tool path: $remote_tool" >&2
            exit 64
            ;;
        /*) ;;
        *)
            printf '%s\n' "remote tool path must be absolute: $remote_tool" >&2
            exit 64
            ;;
    esac
done
case "$linux_cflags" in
    *[!A-Za-z0-9_=./:-]*)
        printf '%s\n' "unsafe SESSION_HOST_LINUX_CFLAGS: $linux_cflags" >&2
        exit 64
        ;;
esac

archive_directory=$(mktemp -d "${TMPDIR:-/tmp}/orion-session-host-linux.XXXXXX")
archive="$archive_directory/source.tar.gz"
remote_archive_pending=0
cleanup_local() {
    status=$?
    trap - EXIT HUP INT TERM
    set +e
    rm -rf "$archive_directory"
    if [ "$remote_archive_pending" -eq 1 ]; then
        # shellcheck disable=SC2086
        "$ssh_command" $ssh_options $keepalive_options "$linux_host" \
            "rm -f $remote_archive"
    fi
    exit "$status"
}
trap cleanup_local EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

COPYFILE_DISABLE=1 tar --no-xattrs -czf "$archive" \
    Makefile \
    session-host/Cargo.toml \
    session-host/Cargo.lock \
    session-host/rust-toolchain.toml \
    session-host/src \
    session-host/tests \
    session-host/protocol \
    agent-protocol/protocol/fixtures

build_id=$(date -u +%Y%m%dT%H%M%SZ)-$$
remote_archive="$remote_root/uploads/source-$build_id.tar.gz"
ssh_options="-T -p $linux_port -o BatchMode=yes -o ConnectTimeout=15"
keepalive_options="-o ServerAliveInterval=15 -o ServerAliveCountMax=12"

# shellcheck disable=SC2086
"$ssh_command" $ssh_options $keepalive_options "$linux_host" \
    "mkdir -p $remote_root/uploads $remote_root/runs $remote_root/cache && \
test -x $linux_cc && test -x $linux_ar && test -x $linux_toolchain/cargo"

remote_archive_pending=1
# shellcheck disable=SC2086
"$scp_command" -P "$linux_port" -o BatchMode=yes -o ConnectTimeout=15 \
    $keepalive_options "$archive" "$linux_host:$remote_archive"

remote_command="set -eu
archive=$remote_archive
run=\$(mktemp -d $remote_root/runs/run.XXXXXX)
cleanup() { rm -rf \"\$run\" \"\$archive\"; }
trap cleanup EXIT HUP INT TERM
tar -xzf \"\$archive\" -C \"\$run\"
cd \"\$run\"
find \"\$run\" -type f -exec touch {} +
env PATH=$linux_toolchain:\$PATH \\
    CARGO_TARGET_DIR=$remote_root/cache/cargo \\
    CC=$linux_cc AR=$linux_ar \\
    CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=$linux_cc \\
    CFLAGS_x86_64_unknown_linux_gnu=$linux_cflags \\
    $linux_toolchain/cargo test --locked --manifest-path session-host/Cargo.toml"

# shellcheck disable=SC2086
"$ssh_command" $ssh_options $keepalive_options "$linux_host" "$remote_command"
remote_archive_pending=0
