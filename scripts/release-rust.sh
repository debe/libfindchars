#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUST_DIR="$PROJECT_ROOT/rust"

# --- Defaults ---
DRY_RUN=false
VERSION=""

# --- Usage ---
usage() {
    cat <<EOF
Usage: $(basename "$0") [--dry-run] <version>

Release the libfindchars Rust crates to crates.io.

Publishes, in dependency order:
  findchars-solver  ->  findchars  ->  findchars-csv

findchars-bench and findchars-examples carry 'publish = false' and are skipped.

Prerequisites:
  - crates.io token configured ('cargo login' or CARGO_REGISTRY_TOKEN)
  - gh CLI authenticated (for GitHub release)
  - clean working tree

Arguments:
  version       Release version (plain semver, e.g. 0.1.0)

Options:
  --dry-run     Package and verify only; skip publish, tag, release
  -h, --help    Show this help

Example:
  $(basename "$0") 0.1.0
  $(basename "$0") --dry-run 0.1.0
EOF
}

# --- Parse args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
        *) VERSION="$1"; shift ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "Error: version argument required" >&2
    usage >&2
    exit 1
fi

# --- Helpers ---
info()  { echo "==> $*"; }
error() { echo "Error: $*" >&2; exit 1; }

# --- Validate prerequisites ---
info "Validating prerequisites"

# cargo available
command -v cargo >/dev/null 2>&1 || error "cargo not found on PATH."

# Clean working tree (untracked files are OK)
if ! git -C "$PROJECT_ROOT" diff --quiet || ! git -C "$PROJECT_ROOT" diff --cached --quiet; then
    error "Working tree has uncommitted changes. Commit or stash first."
fi

# gh CLI authenticated
if ! gh auth status >/dev/null 2>&1; then
    error "GitHub CLI not authenticated. Run 'gh auth login' first."
fi

if [[ "$DRY_RUN" == false ]]; then
    # crates.io token available
    CARGO_HOME_DIR="${CARGO_HOME:-$HOME/.cargo}"
    if [[ -z "${CARGO_REGISTRY_TOKEN:-}" ]] \
        && [[ ! -f "$CARGO_HOME_DIR/credentials.toml" ]] \
        && [[ ! -f "$CARGO_HOME_DIR/credentials" ]]; then
        error "No crates.io token found. Run 'cargo login' or set CARGO_REGISTRY_TOKEN."
    fi

    # Tag must not already exist
    if git -C "$PROJECT_ROOT" rev-parse "rust-v${VERSION}" >/dev/null 2>&1; then
        error "Tag rust-v${VERSION} already exists."
    fi
fi

# --- Verify (on the unmodified tree, before any version bump) ---
# Tests do not depend on the version string, so running them first means a
# verify failure aborts with the working tree still pristine.
info "Verifying: tests and clippy"
cd "$RUST_DIR"
cargo test -p findchars -p findchars-csv
cargo test -p findchars-solver -- --skip auto_split_many
cargo clippy --workspace --all-targets -- -D warnings

# --- Restore-on-failure guard ---
# From here on rust/Cargo.toml and rust/Cargo.lock get modified. If the script
# exits before the release commit, restore them so a failed run leaves no mess.
COMMITTED=false
restore_manifests() {
    if [[ "$COMMITTED" == false ]] \
        && ! git -C "$PROJECT_ROOT" diff --quiet -- rust/Cargo.toml rust/Cargo.lock; then
        echo "==> Restoring rust/Cargo.toml and rust/Cargo.lock (release not committed)" >&2
        git -C "$PROJECT_ROOT" checkout -- rust/Cargo.toml rust/Cargo.lock
    fi
}
trap restore_manifests EXIT

# --- Set version ---
info "Setting workspace version to ${VERSION}"
# The bare `version = "..."` line under [workspace.package]; member crates
# inherit it via `version.workspace = true`.
sed -i -E "s/^version = \"[^\"]*\"/version = \"${VERSION}\"/" "$RUST_DIR/Cargo.toml"
# The `version` field of each inter-crate dependency under
# [workspace.dependencies] (the `path = "...", version = "..."` entries).
sed -i -E "s/(path = \"[^\"]*\", version = )\"[^\"]*\"/\1\"${VERSION}\"/" "$RUST_DIR/Cargo.toml"
# Sync Cargo.lock to the bumped member versions. '--workspace' rewrites only
# the workspace members' own lock entries, leaving registry deps untouched —
# so the staged Cargo.lock stays consistent with Cargo.toml.
cargo update --workspace --manifest-path "$RUST_DIR/Cargo.toml"

# --- Dry run stops here ---
if [[ "$DRY_RUN" == true ]]; then
    info "Dry run: packaging all three crates (tree is dirty from the version bump)"
    # findchars-solver has no path deps — a full --dry-run verify build works.
    cargo publish --dry-run --allow-dirty \
        --manifest-path "$RUST_DIR/findchars-solver/Cargo.toml"
    # findchars and findchars-csv depend on workspace crates not yet on crates.io,
    # so a verify build would need them published. '--no-verify' skips that build
    # while still validating manifest metadata, the file list, and packaging.
    cargo package --no-verify --allow-dirty \
        --manifest-path "$RUST_DIR/findchars/Cargo.toml"
    cargo package --no-verify --allow-dirty \
        --manifest-path "$RUST_DIR/findchars-csv/Cargo.toml"
    info "Dry run complete — rust/Cargo.toml and rust/Cargo.lock restored on exit."
    exit 0
fi

# --- Commit the version bump (clean tree required by 'cargo publish') ---
cd "$PROJECT_ROOT"
git add rust/Cargo.toml rust/Cargo.lock
git diff --cached --quiet || git commit -m "release(rust): ${VERSION}"
COMMITTED=true

# --- Publish in dependency order ---
# 'cargo publish' (>= 1.66) waits for each crate to appear in the index;
# the short sleep is extra margin before the dependent crate resolves it.
info "Publishing findchars-solver ${VERSION}"
cargo publish --manifest-path "$RUST_DIR/findchars-solver/Cargo.toml"
sleep 30

info "Publishing findchars ${VERSION}"
cargo publish --manifest-path "$RUST_DIR/findchars/Cargo.toml"
sleep 30

info "Publishing findchars-csv ${VERSION}"
cargo publish --manifest-path "$RUST_DIR/findchars-csv/Cargo.toml"

# --- Tag and release ---
info "Creating tag rust-v${VERSION}"
git tag -a "rust-v${VERSION}" -m "Rust release ${VERSION}"

info "Pushing commit and tag"
git push origin HEAD
git push origin "rust-v${VERSION}"

info "Creating GitHub release"
gh release create "rust-v${VERSION}" \
    --title "rust-v${VERSION}" \
    --generate-notes

info "Released rust-v${VERSION} to crates.io and GitHub."
