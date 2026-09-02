override SESSION_HOST_RUST_VERSION := $(shell \
	awk '\
		/^[[:space:]]*\[/ { \
			in_toolchain = 0; \
			if ($$0 ~ /^[[:space:]]*\[toolchain\][[:space:]]*(\#.*)?$$/) { \
				toolchain_count++; \
				in_toolchain = 1; \
			} \
			next; \
		} \
		in_toolchain && /^[[:space:]]*channel[[:space:]]*=/ { \
			channel_count++; \
			channel_line = $$0; \
			if (channel_line ~ \
					/^[[:space:]]*channel[[:space:]]*=[[:space:]]*"[0-9]+[.][0-9]+[.][0-9]+"[[:space:]]*(\#.*)?$$/) { \
				sub(/^[^"]*"/, "", channel_line); \
				sub(/".*$$/, "", channel_line); \
				channel_value = channel_line; \
			} \
		} \
		END { \
			if (toolchain_count == 1 && channel_count == 1 && channel_value != "") { \
				print channel_value; \
			} \
		}' \
		"$(CURDIR)/session-host/rust-toolchain.toml")
ifeq ($(strip $(SESSION_HOST_RUST_VERSION)),)
$(error session-host/rust-toolchain.toml must contain exactly one [toolchain] header and one exact \
double-quoted [toolchain].channel stable version MAJOR.MINOR.PATCH)
endif
SESSION_HOST_RUSTUP_VERSION ?= 1.29.1
ORION_CACHE_ROOT ?= $(CURDIR)/.orion-cache
SESSION_HOST_TOOLCHAIN_CACHE ?= $(ORION_CACHE_ROOT)/rust-toolchains
SESSION_HOST_CARGO_TARGET ?= $(ORION_CACHE_ROOT)/session-host-cargo
SESSION_HOST_NATIVE_RESOURCES ?= $(CURDIR)/session-host/target/native-resources
SESSION_HOST_KERNEL := $(shell uname -s)
SESSION_HOST_MACHINE := $(shell uname -m)

ifeq ($(SESSION_HOST_KERNEL):$(SESSION_HOST_MACHINE),Darwin:x86_64)
SESSION_HOST_TRIPLE := x86_64-apple-darwin
SESSION_HOST_RUSTUP_SHA256 := 259e2b84274434085163fe8d556510571772cda2aa6d87ca6aa664f57bc644e3
SESSION_HOST_SHA256 := shasum -a 256
endif
ifeq ($(SESSION_HOST_KERNEL):$(SESSION_HOST_MACHINE),Darwin:arm64)
SESSION_HOST_TRIPLE := aarch64-apple-darwin
SESSION_HOST_RUSTUP_SHA256 := ec1b9233e7f72990ecd8e62063fa7f6c3dfc2bec8e97f88bff165f9100ac696a
SESSION_HOST_SHA256 := shasum -a 256
endif
ifeq ($(SESSION_HOST_KERNEL):$(SESSION_HOST_MACHINE),Darwin:aarch64)
SESSION_HOST_TRIPLE := aarch64-apple-darwin
SESSION_HOST_RUSTUP_SHA256 := ec1b9233e7f72990ecd8e62063fa7f6c3dfc2bec8e97f88bff165f9100ac696a
SESSION_HOST_SHA256 := shasum -a 256
endif
ifeq ($(SESSION_HOST_KERNEL):$(SESSION_HOST_MACHINE),Linux:x86_64)
SESSION_HOST_TRIPLE := x86_64-unknown-linux-gnu
SESSION_HOST_RUSTUP_SHA256 := dda7234360b7f578ca8b0ddcb80145646fa61a67c1720a5abc7051b35c9fcb71
SESSION_HOST_SHA256 := sha256sum
endif
ifeq ($(SESSION_HOST_KERNEL):$(SESSION_HOST_MACHINE),Linux:aarch64)
SESSION_HOST_TRIPLE := aarch64-unknown-linux-gnu
SESSION_HOST_RUSTUP_SHA256 := 15f6e4ce9f583b929c996c91562bad6d4454f3281de858b02cdfdef615fac433
SESSION_HOST_SHA256 := sha256sum
endif
ifeq ($(SESSION_HOST_KERNEL):$(SESSION_HOST_MACHINE),Linux:arm64)
SESSION_HOST_TRIPLE := aarch64-unknown-linux-gnu
SESSION_HOST_RUSTUP_SHA256 := 15f6e4ce9f583b929c996c91562bad6d4454f3281de858b02cdfdef615fac433
SESSION_HOST_SHA256 := sha256sum
endif

SESSION_HOST_TOOLCHAIN_DIR := \
	$(SESSION_HOST_TOOLCHAIN_CACHE)/$(SESSION_HOST_RUST_VERSION)-$(SESSION_HOST_TRIPLE)
SESSION_HOST_RUSTUP_HOME := $(SESSION_HOST_TOOLCHAIN_DIR)/rustup
SESSION_HOST_CARGO_HOME := $(SESSION_HOST_TOOLCHAIN_DIR)/cargo
SESSION_HOST_CARGO := $(SESSION_HOST_CARGO_HOME)/bin/cargo
SESSION_HOST_RUSTC := $(SESSION_HOST_CARGO_HOME)/bin/rustc
SESSION_HOST_RUSTUP_INIT := $(SESSION_HOST_TOOLCHAIN_DIR)/download/rustup-init
SESSION_HOST_RUSTUP_ARCHIVE := https://static.rust-lang.org/rustup/archive
SESSION_HOST_RUSTUP_VERSION_PATH := $(SESSION_HOST_RUSTUP_ARCHIVE)/$(SESSION_HOST_RUSTUP_VERSION)
SESSION_HOST_RUSTUP_TARGET_PATH := $(SESSION_HOST_RUSTUP_VERSION_PATH)/$(SESSION_HOST_TRIPLE)
SESSION_HOST_RUSTUP_URL := $(SESSION_HOST_RUSTUP_TARGET_PATH)/rustup-init
SESSION_HOST_NATIVE_DIRECTORY := \
	$(SESSION_HOST_NATIVE_RESOURCES)/META-INF/orion/native/session-host/$(SESSION_HOST_TRIPLE)

.PHONY: session-host-build session-host-fixtures session-host-prepare session-host-test

session-host-prepare:
	@test -n "$(SESSION_HOST_TRIPLE)" || { \
		echo "unsupported Rust bootstrap host: $(SESSION_HOST_KERNEL) $(SESSION_HOST_MACHINE)" >&2; \
		exit 69; \
	}
	@mkdir -p "$(SESSION_HOST_TOOLCHAIN_CACHE)" "$(SESSION_HOST_CARGO_TARGET)"
	@if [ ! -x "$(SESSION_HOST_CARGO)" ]; then \
		mkdir -p "$(SESSION_HOST_TOOLCHAIN_DIR)/download" \
			"$(SESSION_HOST_RUSTUP_HOME)" "$(SESSION_HOST_CARGO_HOME)"; \
		curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
			"$(SESSION_HOST_RUSTUP_URL)" \
			--output "$(SESSION_HOST_RUSTUP_INIT)"; \
		if ! printf '%s  %s\n' "$(SESSION_HOST_RUSTUP_SHA256)" "$(SESSION_HOST_RUSTUP_INIT)" | \
			$(SESSION_HOST_SHA256) -c -; then \
			echo "rustup-init checksum verification failed" >&2; \
			exit 71; \
		fi; \
		chmod 755 "$(SESSION_HOST_RUSTUP_INIT)"; \
		RUSTUP_HOME="$(SESSION_HOST_RUSTUP_HOME)" CARGO_HOME="$(SESSION_HOST_CARGO_HOME)" \
			"$(SESSION_HOST_RUSTUP_INIT)" -y --no-modify-path --profile minimal \
			--default-toolchain "$(SESSION_HOST_RUST_VERSION)"; \
	fi
	@rustc_version=$$( \
		RUSTUP_HOME="$(SESSION_HOST_RUSTUP_HOME)" CARGO_HOME="$(SESSION_HOST_CARGO_HOME)" \
			"$(SESSION_HOST_RUSTC)" --version \
	); \
	case "$$rustc_version" in \
		"rustc $(SESSION_HOST_RUST_VERSION) "*) ;; \
		*) echo "unexpected rustc version: $$rustc_version" >&2; exit 70 ;; \
	esac
	@cargo_version=$$( \
		RUSTUP_HOME="$(SESSION_HOST_RUSTUP_HOME)" CARGO_HOME="$(SESSION_HOST_CARGO_HOME)" \
			"$(SESSION_HOST_CARGO)" --version \
	); \
	case "$$cargo_version" in \
		"cargo $(SESSION_HOST_RUST_VERSION) "*) ;; \
		*) echo "unexpected Cargo version: $$cargo_version" >&2; exit 70 ;; \
	esac

session-host-build: session-host-prepare
	@cd session-host && RUSTUP_HOME="$(SESSION_HOST_RUSTUP_HOME)" CARGO_HOME="$(SESSION_HOST_CARGO_HOME)" \
		CARGO_TARGET_DIR="$(SESSION_HOST_CARGO_TARGET)" "$(SESSION_HOST_CARGO)" build --locked --release
	@mkdir -p "$(SESSION_HOST_NATIVE_DIRECTORY)"
	@cp "$(SESSION_HOST_CARGO_TARGET)/release/session-host" "$(SESSION_HOST_NATIVE_DIRECTORY)/session-host"
	@chmod 755 "$(SESSION_HOST_NATIVE_DIRECTORY)/session-host"

session-host-test: session-host-prepare
	@cd session-host && RUSTUP_HOME="$(SESSION_HOST_RUSTUP_HOME)" CARGO_HOME="$(SESSION_HOST_CARGO_HOME)" \
		CARGO_TARGET_DIR="$(SESSION_HOST_CARGO_TARGET)" "$(SESSION_HOST_CARGO)" test --locked

session-host-fixtures: session-host-prepare
	@cd session-host && RUSTUP_HOME="$(SESSION_HOST_RUSTUP_HOME)" CARGO_HOME="$(SESSION_HOST_CARGO_HOME)" \
		CARGO_TARGET_DIR="$(SESSION_HOST_CARGO_TARGET)" "$(SESSION_HOST_CARGO)" run --locked --release \
		--bin generate-protocol-fixtures
