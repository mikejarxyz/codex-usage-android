# Building

## Toolchain

- Android SDK platform 36 and Build Tools 35+.
- Android Gradle Plugin 8.10.1, Gradle 8.12, and JDK 17+.
- Android NDK 27.0.12077973 with the Android API 26 ARM64 compiler wrappers.
- Rust 1.98.0 and its `aarch64-linux-android` standard library.
- Python 3.10+ for generating dependency notices and the publication audit.

The checked-in Gradle wrapper pins Gradle 8.12 and verifies the distribution
checksum. GitHub Actions builds the pinned native inputs on Ubuntu without
account credentials and uploads unsigned beta artifacts. Linux is the CI-tested
bootstrap target; macOS support in the helper is not yet validated.

## Native prerequisites

On Linux, install Git, Perl, Make, Rustup/Cargo, the pinned SDK/NDK, and JDK.
Set `ANDROID_HOME`, then run `bash scripts/build-native.sh`. The helper checks out
exact Codex/OpenSSL commits under ignored `.probe/`, builds static OpenSSL and the
ARM64 executable, and leaves account data untouched.

On Windows with prepared native dependencies:

1. Clone [OpenAI Codex](https://github.com/openai/codex) into `.probe/codex` and
   check out `cc7ba33601286a751477832b01de7b6f8de0dd9c`. Do not change its lockfile.
2. Build official [OpenSSL](https://github.com/openssl/openssl) 3.6.3 from commit
   `aae016bfd52fcad2bc9657c2c782cfdf73b1ed5f` for Android ARM64. Configure with
   `android-arm64 -D__ANDROID_API__=26 no-shared no-tests` using the NDK toolchain,
   Perl, and Make. Install headers and static `libssl.a`/`libcrypto.a` into
   `.probe/openssl-android`. No auth or account files are needed.
3. Run `./scripts/build-app-server-android.ps1`. It verifies the Codex commit,
   builds the native executable, and packages it under `.probe/packaged-native`.
   Both 16 KB linker flags are applied to the final executable.
4. Regenerate dependency notices as described in `licenses/README.md`.

With the Android SDK and Java environment configured, run:

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:bundleRelease
```

`verifyNativePageSizes` runs before packaging and rejects missing binaries,
incompatible LOAD segments, or an unaligned RELRO end. Native libraries remain
compressed/extracted because the app launches a child executable from its
installed native-library directory.

The release bundle is not production-signed until a private signing configuration
is supplied. Never commit a keystore, signing password, or an account session.
Do not publish `.probe/`, local diagnostic journals, or generated APK/AAB files
as repository source. GitHub Releases can distribute deliberately prepared builds.

## Device checks

The test runner is `com.example.codexusage.UsageChecks`. Run its ordinary mode on
a development phone already signed in; it checks real quota reads and a scheduled
background job. It briefly ages only the sanitized display cache and restores it
if the check fails. It does not export or replace auth credentials.

`-PisolatedChecks` builds a separate `com.example.codexusage.checks` installation.
Run instrumentation with `-e signOutCheck true` for official logout, or
`-e hardeningCheck true` for cache-write failure, backend timeout, local sign-out
recovery, and both widget layouts. These modes require the isolated package and
use synthetic data without affecting a user's main installation.

`-e compatibilityCheck true` is for an API 36 x86_64 16 KB emulator. It uses
synthetic quota, checks window insets, and verifies actual ARM64 app-server
initialization through Android native translation. It is not a physical ARM64
hardware test.

For permanent identity, private signing, and Play rollout, see [RELEASING.md](RELEASING.md).

For checks against the non-debuggable signed release, add `-PreleaseChecks`
and the private signing configuration when building `:app:assembleReleaseAndroidTest`.
The test APK shares the release certificate. Use `-e compatibilityCheck true`
on the 16 KB emulator; never run its synthetic-cache mode on a signed-in phone.

After editing PRIVACY.md, run `python scripts/generate-privacy.py` to update the
offline in-app copy. CI checks that both copies agree.
