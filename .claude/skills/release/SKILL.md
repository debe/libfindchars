---
name: release
description: Release libfindchars to Maven Central (Java) or crates.io (Rust) — version and tag formats, published artifacts, prerequisites, and the release scripts. Use when cutting a release or asking how versioning and tagging work in this repo.
---

# Releasing libfindchars

```bash
# Release Java to Maven Central (builds, signs, uploads, tags, creates GitHub release)
scripts/release-java.sh 0.4.1-jdk25-preview
scripts/release-java.sh --dry-run 0.4.1-jdk25-preview

# Release Rust crates to crates.io (publishes in dependency order, tags, GitHub release)
scripts/release-rust.sh 0.1.0
scripts/release-rust.sh --dry-run 0.1.0
```

**Version format**: `{semver}-jdk{N}-preview` (e.g. `0.5.0-jdk25-preview`). Drop the `-jdk25-preview` suffix when the Vector API graduates from incubator. The Rust crates version independently on crates.io with plain semver.

**Tag format**: `v{version}` for Java (e.g. `v0.5.0-jdk25-preview`); `rust-v{version}` for the Rust crates (e.g. `rust-v0.1.0`).

**Published artifacts**: `libfindchars-api`, `libfindchars-compiler`. CSV, examples, and bench skip deployment.

**Prerequisites**:
- GPG signing key available to `gpg-agent`
- `~/.m2/settings.xml` with `<server id="central">` credentials (Central Portal token)
- `gh auth login` (GitHub CLI authenticated)
