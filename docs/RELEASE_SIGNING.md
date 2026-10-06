# Signed release builds with GitHub Actions

## Workflows
| File | When | Result |
|---|---|---|
| `.github/workflows/ci.yml` | every push / PR to `main` | runs the unit tests, lint, builds a **debug** APK (artifact `MeshChat-debug-apk`; debug-signed, for testing only) |
| `.github/workflows/release.yml` | push of a tag `v*` (e.g. `v1.0.0`) or manual run | runs tests, builds a **signed release APK**, verifies the signature with `apksigner`, attaches `MeshChat-<version>.apk` + `.sha256` to a GitHub Release |

## One-time setup
1. Create the keystore (needs a JDK for `keytool`):
   `./scripts/generate-keystore.sh meshchat release.jks`
   Back up `release.jks` and both passwords somewhere safe and **private**. Losing it means users can never install updates over an existing install.
2. In GitHub: Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret:
   - `MESHCHAT_KEYSTORE_BASE64` — content of `release.jks.base64`
   - `MESHCHAT_KEYSTORE_PASSWORD`
   - `MESHCHAT_KEY_ALIAS` (default `meshchat`)
   - `MESHCHAT_KEY_PASSWORD`
3. Delete `release.jks.base64` from your computer afterwards (keep `release.jks` backed up).

## Releasing
```
git tag v1.0.0
git push origin v1.0.0
```
`versionName` = tag without the `v`; `versionCode` = the workflow run number (always increasing, which Android requires for updates).
Manual alternative: Actions ▸ "Release (signed APK)" ▸ Run workflow ▸ enter the version.

## Local signed build
```
export MESHCHAT_KEYSTORE_FILE=/path/release.jks MESHCHAT_KEYSTORE_PASSWORD=... MESHCHAT_KEY_ALIAS=meshchat MESHCHAT_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```
Without these variables `assembleRelease` produces an **unsigned** APK, which Android will not install; the CI release job fails instead of publishing it.

## Notes
- The keystore is written to the runner's temp dir only for the build and deleted afterwards. Secrets are not available to PRs from forks, so only `ci.yml` (no secrets) runs for them.
- APK is signed with v1+v2+v3 schemes (minSdk 26). For Google Play use an AAB (`bundleRelease`) and Play App Signing; this setup is for direct APK distribution.
- Minification (R8) is off: the app could not be tested on a device here, and enabling shrinking without testing risks breaking Room/reflection at runtime. Turn on `isMinifyEnabled` once you have verified on devices.
- The Android code has not been compiled in my environment; if the first CI run fails, open the failed step log and send me the error.
