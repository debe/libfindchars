---
name: release
description: Release libfindchars to Maven Central (Java) or crates.io (Rust) — version and tag formats, published artifacts, prerequisites, and the release scripts. Use when cutting a release or asking how versioning and tagging work in this repo.
---

# Releasing libfindchars

```bash
# Release Java to Maven Central (builds, signs, uploads, tags, creates GitHub release)
scripts/release-java.sh 0.6.0-jdk25
scripts/release-java.sh --dry-run 0.6.0-jdk25

# Release Rust crates to crates.io (publishes in dependency order, tags, GitHub release)
scripts/release-rust.sh 0.1.0
scripts/release-rust.sh --dry-run 0.1.0
```

Both `--dry-run` modes leave the working tree untouched (version bumps are restored on exit).

**Version format**: `{semver}-jdk{N}` for Java (e.g. `0.6.0-jdk25` — the `-preview` suffix was dropped in 0.6.0 since no preview features are used; the `-jdk{N}` part stays until the Vector API graduates from incubator). The Rust crates version independently on crates.io with plain semver.

**Tag format**: `v{version}` for Java (e.g. `v0.6.0-jdk25`); `rust-v{version}` for the Rust crates (e.g. `rust-v0.1.0`).

**Published artifacts**: Java — `libfindchars-api`, `libfindchars-compiler` (CSV, examples, and bench skip deployment). Rust — `findchars-solver`, `findchars`, `findchars-csv`, published in that dependency order (`findchars-bench` and `findchars-examples` are `publish = false`).

**Prerequisites (Java)**:
- JDK 25 — on macOS: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`
- GPG signing key available to `gpg-agent`
- `~/.m2/settings.xml` with `<server id="central">` credentials (Central Portal token)
- `gh auth login` (GitHub CLI authenticated)

**Prerequisites (Rust)**:
- crates.io token: `cargo login` or `CARGO_REGISTRY_TOKEN`
- C++ toolchain + CMake for the vendored Z3 build (first build ~5 min, cached)
- `gh auth login` (GitHub CLI authenticated)
