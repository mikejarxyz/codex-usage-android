# Privacy

Effective date: October 11, 2026.

Codex Usage for Android is an unofficial, free, open-source app maintained in
the [project repository](https://github.com/mikejarxyz/codex-usage-android).
It has no advertising or developer-operated server. The app explicitly disables
the bundled app-server's analytics and telemetry exporters.

## Sign-in and network requests

The app runs the official Codex app-server on your device. Signing in opens
OpenAI's sign-in page in your browser. Authentication and usage requests go to
OpenAI over encrypted connections. Your account is governed by
[OpenAI's privacy policy](https://openai.com/policies/privacy-policy/).
This app does not create a separate account or send prompts or conversations.
OpenAI may process account identifiers, authentication information, connection
metadata, and usage requests when providing its service.

## Information stored on your device

The app-server stores your sign-in credentials in Android's private app storage.
The app stores a separate display cache containing usage percentages, limit
durations, reset times, available reset counts and expiries, and the time of the
last successful update. A local diagnostic file records restricted status flags
and displayable quota information, without raw authentication/account responses
or server error output. Preferences store refresh status and scheduling times.
Android backups are disabled. Other apps cannot normally access this private
storage. Anyone who can see your home-screen widget can see the quota it displays.

## Retention and deletion

Successful updates replace the display cache. Failed requests retain the last
successful values. Sign out removes the saved sign-in and display cache; it does
not delete your OpenAI account or revoke every session on other devices.
Clear the app's storage or uninstall it to remove its remaining local files and
preferences. Manage your OpenAI account and data directly with OpenAI.

## Permissions and background activity

Internet and network-state access support sign-in and quota reads. A temporary
foreground service supports a refresh or sign-in in progress. Android schedules
background refresh while a widget is installed; device power policies may delay
it. The app does not request location, contacts, camera, microphone, or shared
storage access.

## Contact and changes

For project questions, contact the maintainer through the
[project's issue tracker](https://github.com/mikejarxyz/codex-usage-android/issues).
Issues are public: never include credentials, account responses, or personal
information. For a private security report, use GitHub's private vulnerability
reporting feature if enabled. Policy changes will be recorded in this repository.

Before a Play Store release, the publisher must add a suitable direct privacy
contact and review this policy against the final distributed build.
