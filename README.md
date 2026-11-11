# Clockrove

Local-first Android timekeeping, location history and job boards. Implementation
is in milestone 2: manual timer controls, native persistence and location
integrations are being validated as a development prototype. Android launch
requires all milestones 1–8 in [app_plan.md](app_plan.md). Track individual tasks
and actual validation in [plan_status.md](plan_status.md).

## Rights and contribution

Proposed source-available personal-use and contribution terms await owner review
in [docs/SOURCE_TERMS_DRAFT.md](docs/SOURCE_TERMS_DRAFT.md). The repository is public by explicit owner instruction; the draft is not an
effective license and does not grant open-source rights. Read [CONTRIBUTING.md](CONTRIBUTING.md).

## Toolchain and setup

Pinned Flutter: **3.47.9**, bundled Dart **3.13.7**. Official downloads and checksums:
https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json

Builds and tests run exclusively through GitHub Actions; do not execute build,
analysis, formatting or test commands on this development VM. Edit and review
source locally, push a reviewed change, then inspect the Validate workflow run.
The workflow installs pinned tools, resolves the lockfile, and runs checks on
GitHub-hosted ubuntu-24.04. Publishing is not configured.

CI JDK: Eclipse Temurin **17.0.17+10** (exact setup-java-compatible pin). Android build compatibility is being validated.
Do not interpret analysis success as Android build or device validation. Generated
Gradle inputs select AGP 9.1.0, Kotlin 2.4.0 and Gradle 9.3.1; minimum SDK **24**, compile/target SDK **36**, build tools **36.0.0** and NDK
**28.2.13676358** are pinned. API 24 follows the selected Flutter minimum;
recheck the target API against Play requirements before release.
Release signing and store publishing are unconfigured.

On this ARM64 development host, the official Flutter Linux archive bundled x64
Dart. The matching official ARM64 Dart 3.13.7 SDK was installed into the ignored
local Flutter cache and flutter_tools.snapshot rebuilt. Android native tools may
also require a compatible build host. On ARM64, Gradle's Maven AAPT2 is x64;
set `android.aapt2FromMavenOverride=/absolute/path/to/arm64/aapt2` only in a
local Gradle user properties file after validating that tool. Never commit a
host path to Android build settings. See the status record for actual evidence.

## Protected workspace Git mount

This workspace's root `.git` is a read-only empty mount. Local metadata is kept
in ignored `.local/git`; use `scripts/git-local status` and other normal Git
subcommands through that wrapper. Initialize it in a fresh protected workspace:

```sh
mkdir -p .local
git init --bare --initial-branch=main .local/git
scripts/git-local status --short
```

Ordinary clones use normal `git`. No remote is configured by the wrapper. Review
staged paths and source terms before creating or pushing a public repository.
Never publish `.local-skills`, `.tools`, `.local`, real data, or credentials.

Architecture and dependency inventory are in `docs/`. No private local skill is
required to build or contribute. The validation-only GitHub workflow runs formatting, analysis, implemented tests,
Android debug build and native unit tasks without publishing credentials. No
Android device, CI run or store release has been validated yet.
