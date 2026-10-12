# Release preparation

This is a beta release checklist, not a claim that the app is ready for Play.

## Decisions needed from the publisher

- Permanent application ID; suggested `io.github.mikejarxyz.codexusage`.
  The existing development installation uses `com.example.codexusage`.
- Play developer account, displayed developer identity, and a direct support/privacy email.
- Upload-key ownership and secure backup. Let Play App Signing manage the Play
  distribution key unless there is a specific reason to manage it yourself.
- Final screenshots, launcher artwork, and review of OpenAI trademark use.

## Private signing

Create an upload key with Android Studio's signed-bundle wizard. Keep the
keystore and passwords outside the repository and back them up securely.
Create an external `signing.properties` containing `storeFile` (absolute path),
`storePassword`, `keyAlias`, and `keyPassword`. Do not paste its contents into
issues or chat; do not pass passwords on command lines.

With that private file and the approved identity, build:

```text
./gradlew :app:bundleRelease -PproductionRelease -PreleaseApplicationId=io.github.mikejarxyz.codexusage -PreleaseSigningFile=/absolute/private/signing.properties
```

The production flag rejects a missing signing configuration or placeholder
identity. Ordinary CI builds remain unsigned and cannot be uploaded to Play.
Debug builds retain the development application ID. A production ID installs
as a separate app and requires its own sign-in.

Verify the final bundle signature and inspect its manifest/package identity,
permissions, included notices, and native page alignment. Test the signed build
using Play internal testing before moving to closed or production tracks.
For GitHub APK distribution, choose a stable signing identity deliberately;
Play-distributed APKs use Play's app-signing certificate, not the upload key.

## Store listing draft

Name: **Codex Usage**

Short description: **See your Codex usage limits at a glance, in the app or a home-screen widget.**

Full description:

Keep an eye on your remaining Codex usage with a simple Android app and widget.
View the usage windows returned for your account, remaining percentages,
progress bars, reset times, and the last successful update. See available usage
resets and their expiry dates when OpenAI provides them. Resets are read-only.

Sign in with ChatGPT through your browser. Authentication stays in private app
storage; requests go directly to OpenAI. No ads, paid features, analytics, or
developer-operated backend. The source code and dependency licenses are public.

The widget supports manual refresh and scheduled background refresh. Android's
battery and idle policies can delay updates. ARM64 devices running Android 8 or
newer are supported. This unofficial app is not affiliated with or endorsed by
OpenAI. An eligible OpenAI account is required; available limits depend on your
account. Upstream authentication and usage endpoints may change.

## Privacy and Data safety review

Publish a stable, publicly accessible privacy-policy page and add its URL to
Play Console. The in-app Privacy menu contains a local copy. Before release,
replace the draft contact instructions with the publisher's direct email in
both copies. Do not claim that no data leaves the device: OpenAI authentication
and quota requests are network transfers, even without a developer backend.

`python scripts/generate-privacy.py` regenerates the in-app asset and
`docs/privacy/index.html`. After the policy/contact review, GitHub Pages can serve
the repository's `main` branch `/docs` folder. The expected URL is
`https://mikejarxyz.github.io/codex-usage-android/privacy/`; verify it is live before
using it in Play Console. Pages hosting has not been enabled by this build setup.

Review Google's Data safety definitions against the bundled app-server and
OpenAI's current processing. Account identifiers/authentication and connection
metadata need particular review. User-initiated transfers may qualify for an
exception to "sharing"; that does not automatically exempt "collection".
Local-only quota/cache files are not off-device collection. No advertising or
analytics purpose is present. Do not label OpenAI processing ephemeral without
evidence of its retention. These are review notes, not final form answers.

## Validation and rollout

- CI source/security audit, pinned native build, license verification, APK/AAB
  packaging, and lint must pass.
- Run the device checks in BUILDING.md. Check sign-in cancellation, offline
  retry, sign-out, both quota windows, resize, reboot scheduling, and power-saving
  behavior on the final signed build.
- Run API 36 / 16 KB emulator checks and Play pre-launch reports. Physical ARM64
  16 KB testing is still desirable; an ARM-translated emulator does not prove
  every native behavior on real ARM hardware.
- Configure privacy policy, Data safety, content rating, ads declaration (no
  ads), app access instructions, and testing tracks in Play Console. Do not
  publish personal test-account credentials in the repository.
- New personal accounts may need 12 opted-in closed testers for 14 continuous
  days before applying for production access; check the account's actual status.

References: [app signing](https://developer.android.com/studio/publish/app-signing),
[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469),
[privacy requirements](https://support.google.com/googleplay/android-developer/answer/10144311),
[testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).
