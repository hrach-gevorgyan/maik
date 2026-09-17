# Releasing

maik is released from a tag. Nothing is built by hand and uploaded.

> maik is a small phone-sized model for offline use — on a plane, in a tunnel, on a
> foreign SIM. Its answers are weak compared to a data-centre assistant and are never
> guaranteed. The app says this once, on the first-run screen; see
> [the disclaimer](../README.md#what-to-expect) for the wording everything else points at.

## One release, start to finish

```bash
# 1. the changelog entry comes first — the release notes are read from it
$EDITOR CHANGELOG.md

# 2. the version in the build file and the tag must agree
$EDITOR app/build.gradle.kts     # maikVersionName / maikVersionCode

git commit -am "3.0.0: ..."
git push origin main

# 3. the tag is what actually releases
git tag v3.0.0 && git push origin v3.0.0
```

The workflow then derives `versionCode` from the tag (`3.0.0` → `30000`), verifies the
model catalogue, runs the golden model test on an emulator, builds, signs, checks the
signature with `apksigner`, and publishes a release whose notes are that version's
section of [`CHANGELOG.md`](../CHANGELOG.md).

A release fails, on purpose, if:

- the tag and `maikVersionName` disagree,
- `CHANGELOG.md` has no section for the version,
- the model catalogue does not verify,
- the golden test cannot get an answer out of a real model.

## Versioning

[Semantic versioning](https://semver.org). The middle number moves when maik gains
something, the last one when something is fixed. `versionCode` is the version name
with two digits per part: `3.0.0` → `30000`, `3.1.4` → `30104`.

Version 3.0 is the baseline: the first release audited end to end, with the
documentation reshaped around it. Treat its behaviour as the reference point.

## Signing

**Releases are debug-signed today.** Every build therefore carries a different key,
and installing a new version requires uninstalling the old one — which also removes
chats and downloaded models. This is the single biggest thing standing between maik
and being pleasant to update.

To fix it permanently, generate a keystore once:

```bash
keytool -genkey -v -keystore maik.jks -keyalg RSA -keysize 2048 -validity 10000 -alias maik
```

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 maik.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `maik` |
| `KEY_PASSWORD` | the key password |

The release workflow picks them up automatically and stops falling back to the debug
key. Keep `maik.jks` out of the repository and safe: lose it and nobody running a
build signed with it can ever be upgraded.

## What CI runs

| Workflow | When | What it does |
|---|---|---|
| [`build.yml`](../.github/workflows/build.yml) | every push and pull request | unit tests, lint on debug and release, both APKs |
| [`golden.yml`](../.github/workflows/golden.yml) | pushes touching code | boots an emulator and makes a real model answer |
| [`release.yml`](../.github/workflows/release.yml) | tags matching `v*` | version check, golden test, signed build, GitHub release |

Lint errors fail the build. Warnings do not.

## Changelog

Written for people, not for parsers: what changed and why it matters, in sentences.
Newest first. The release notes are lifted verbatim, so anything written there is
what the world reads.
