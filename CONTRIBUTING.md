# Contributing

<!-- index: design | Human contribution and review flow | A person or agent prepares a branch or pull request. -->

Thanks for helping with FLUX. This is a small Android app with a real-time audio
core, so the bar is: reproduce first, keep changes scoped, and verify on the JVM
(and on a device when the change is audible or visible).

## Quick start

```bash
git clone https://github.com/TomSzenessy/flux-dj.git
cd flux-dj
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Requirements are JDK 17 and Android SDK 36. If Gradle cannot find the SDK, create
`local.properties` with `sdk.dir=/path/to/Android/sdk`. The full setup,
device-test and release notes are in [`docs/development.md`](./docs/development.md).

## Before changing code

1. Read [`README.md`](./README.md), [`docs/README.md`](./docs/README.md),
   [`docs/architecture/app.md`](./docs/architecture/app.md) and the nearest
   scoped instructions in [`AGENTS.md`](./AGENTS.md).
2. Search GitHub Issues and [`docs/ERROR_LOG.md`](./docs/ERROR_LOG.md). For a
   behavior change, open or update an issue-backed write-ahead record using
   [`docs/ISSUE_TEMPLATE.md`](./docs/ISSUE_TEMPLATE.md).
3. Run the existing tests first, so a new failure is not mistaken for a
   pre-existing one.

## Change and review

- Keep the change scoped to the issue; split unrelated cleanup into its own
  issue.
- Add or update a regression test and the owning document in the same change.
- Run focused checks, then `./gradlew :app:testDebugUnitTest :app:lintDebug`.
  If Python 3.11+ is available, `make check` also validates docs/issue shape and
  `make done` runs the repository gates.
- Audio changes: do not allocate, do file I/O, log or take blocking locks inside
  the mixer render loop. Follow
  [`.agents/rules/audio-render-loop.md`](./.agents/rules/audio-render-loop.md).
- UI changes: keep the 48 dp touch targets, content descriptions and the
  compact-layout rules in [`docs/design.md`](./docs/design.md).
- Include the commands you ran and the observed result in the pull request.
  State any external gate (device, provider, CI) that remains open.

## Commits and pull requests

Small commits with a clear intent are preferred. PRs should link the issue
(`Fixes #N` or `Refs #N`) and include evidence. Dependabot PRs are reviewed as
dependency changes; the maintainer still reviews the diff and the build.

The repository uses the [AI_TEMPLATE](https://github.com/TomSzenessy/AI_TEMPLATE)
agent kit for docs bindings, issue contracts and local gates. You do not need
the kit to build the app, but its docs explain the non-obvious rules.

## Security

Do not open a public issue for an unpatched vulnerability. Use the private
route in [`SECURITY.md`](./SECURITY.md).
