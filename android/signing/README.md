# Android release signing (T312)

Release APKs are signed only when the build sees these environment variables. Without them the
release build is unsigned and still builds (local `make check` and `make apk` need no secrets).

| Env var | Set by | Holds |
|---|---|---|
| `ANDROID_KEYSTORE_PATH` | CI (decoded from a secret) | path to the `.jks` file |
| `ANDROID_KEYSTORE_PASSWORD` | GitHub secret | keystore password |
| `ANDROID_KEY_ALIAS` | GitHub secret | key alias |
| `ANDROID_KEY_PASSWORD` | GitHub secret | key password |

Never commit a keystore or a password. Keep the `.jks` outside the repo (for example `~/keys/`).

## 1. Generate the keystore (once)

```sh
mkdir -p ~/keys && cd ~/keys
keytool -genkeypair -v \
  -keystore kotobaya-release.jks -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias kotobaya
```

`keytool` asks for the store and key passwords. Use the same password for both, or keep them
different and set both secrets.

## 2. Set the four secrets

The workflow reads `ANDROID_KEYSTORE_BASE64` (the keystore, base64-encoded), not the `.jks` file.

```sh
base64 -w0 ~/keys/kotobaya-release.jks > ~/keys/kotobaya-release.jks.b64
gh secret set ANDROID_KEYSTORE_BASE64 < ~/keys/kotobaya-release.jks.b64
gh secret set ANDROID_KEYSTORE_PASSWORD      # prompts; keeps the value out of shell history
gh secret set ANDROID_KEY_ALIAS              # value: kotobaya (or the alias you chose)
gh secret set ANDROID_KEY_PASSWORD           # prompts
rm ~/keys/kotobaya-release.jks.b64
```

`GITHUB_TOKEN` is provided by Actions and needs no setup.

## 3. Publish a release

Push a tag `v<major>.<minor>.<patch>`. `.github/workflows/android-release.yml` builds both flavors,
checks each APK's signature and version, and creates a GitHub release with two assets:

- `kotobaya-<version>.apk`: the default, package `com.ichi2.anki` (AnkiDroid's package name).
- `kotobaya-own-id-<version>.apk`: package `kotobaya.android`, for phones that keep AnkiDroid installed.

The default APK cannot be installed while AnkiDroid is installed (different signing key). Uninstall
AnkiDroid first, or use the `own-id` APK.

To test the signing locally with a throwaway key:

```sh
ANDROID_KEYSTORE_PATH=/path/to/throwaway.jks ANDROID_KEYSTORE_PASSWORD=... \
  ANDROID_KEY_ALIAS=... ANDROID_KEY_PASSWORD=... \
  ./gradlew --no-daemon :android:assembleAnkidroidRelease
$ANDROID_HOME/build-tools/<version>/apksigner verify --verbose --print-certs \
  android/build/outputs/apk/ankidroid/release/kotobaya-release.apk
```

## 4. Install and update with Obtainium

1. In Obtainium, add the app by GitHub URL: `https://github.com/<owner>/kotobaya`.
2. Set the APK filter (regular expression) so only the default asset matches:
   `^kotobaya-[0-9][^/]*\.apk$`
   It excludes `kotobaya-own-id-*.apk`. Use `^kotobaya-own-id-[0-9][^/]*\.apk$` for the other flavor.
3. Install. Obtainium detects later tags as updates. Updates install only when the new APK is signed
   with the same key as the installed one.

## If the signing key is lost

Android accepts updates only from the same key. Without the original `.jks` and its passwords, no
new APK can update an installed copy. Users must uninstall the app first, which deletes its local data;
anything not yet synced to the server is lost. Then publish under a new key and ask users to reinstall.
Keep an offline backup of the `.jks` and both passwords (for example in a password manager).
