# Security policy

<!-- index: design | Vulnerability disclosure and security contact | A vulnerability or security-sensitive change is involved. -->

FLUX is a local-first Android app. It has no hosted service, no account system
and no telemetry; the main security-sensitive paths are the Plex/Drive clients,
the rekordbox XML parser, the media cache, and the release signing setup.

## Reporting a vulnerability

**Please report privately, not in a public issue.**

Use GitHub's private vulnerability reporting:

<https://github.com/TomSzenessy/flux-dj/security/advisories/new>

If that form is unavailable, open a minimal public issue that says only that you
have a security report and ask the maintainer to open a private channel. Do not
include exploit details, tokens, personal data or a working proof of concept in
a public issue.

A useful report contains:

- a short description and the affected commit/version,
- the impact (what an attacker gains, what data or device state is exposed),
- reproduction steps in the private channel, and
- a suggested mitigation if you have one.

## Supported versions

Only the current `main` branch is supported. There are no long-term release
branches yet; fixes land on `main` and are included in the next tagged build.

## Response expectations

This is a volunteer, single-maintainer project. Reports are triaged on a
best-effort basis; no response SLA is promised. Valid reports are fixed on
`main`, recorded in the regression suite where possible, and disclosed after a
fix is available. The maintainer decides on coordinated disclosure timing.

## Scope notes

- **In scope:** app code, Gradle/CI, the XML and HTTP clients, the Plex token
  handling, cache/storage permissions, and the release workflow.
- **Out of scope:** vulnerabilities in Android, Plex/Google services, the user's
  own server, or third-party media files; denial of service caused solely by a
  local file the user chose to import (still report parsing crashes).
- The repository contains **no API keys or production credentials**. Release
  signing values are supplied through environment variables
  (`FLUX_KEYSTORE`, `FLUX_KEYSTORE_PASSWORD`, `FLUX_KEY_ALIAS`,
  `FLUX_KEY_PASSWORD`) and must never be committed.
- CI uses read-only permissions by default. Only the tag-gated release job gets
  write access; fork/untrusted changes do not receive signing values.

## Agent and CI boundaries

Agents and contributors must not deploy, rotate credentials, delete user data or
publish artifacts without explicit authorization. See
[`docs/security.md`](./docs/security.md) for the engineering baseline and
[`docs/development.md`](./docs/development.md) for release setup.

If a credential is ever committed, treat it as compromised: revoke/rotate it
first, then remove it from history and the working tree. Do not rely on deleting
a file in a later commit.
