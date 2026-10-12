# Codex Usage for Android

An **unofficial**, free, open-source Android app and home-screen widget for
monitoring Codex usage limits. No ads or paid features. This project is in beta;
it is not affiliated with or endorsed by OpenAI.

## Features

- Remaining quota, progress bars, and reset times for the returned usage windows.
- Available usage resets and their expiries, displayed read-only.
- A compact home-screen widget with manual refresh and scheduled background refresh.
- Browser sign-in and sign-out, using an on-device build of the official Codex app-server.

Account authentication stays in private app storage. The widget cache contains
only displayable quota data, not credentials. Android backups are disabled.
The app has no developer-operated backend, analytics, or advertising SDK.
Sign-in and quota requests communicate with OpenAI; this is not an offline app.

Android schedules automatic refresh about every 15 minutes while a widget is
installed. Battery/idle policies can delay it. Recent data and recent automatic
attempts are skipped; removing the last widget cancels the schedule. Failed
refreshes preserve the last successful values and timestamp. The widget currently
shows the quota window with the least remaining percentage.

## Build and compatibility

See [BUILDING.md](BUILDING.md) for the pinned native dependencies and Android build.
The app supports Android 8+ on ARM64 phones and targets Android 16 (API 36).
The build checks native LOAD/RELRO segments for 16 KB compatibility.
Tests cover quota semantics, layout, manual refresh, background jobs, and cooldown.
API 36 layout and native startup have also been checked in a 16 KB emulator using
Android ARM translation. Broader device and battery-policy testing remains needed.

Production signing and Play Store setup are not configured yet. Keep upload keys,
credentials, local diagnostics, and generated artifacts outside source control.

## License and third-party code

Original project code is licensed under [Apache-2.0](LICENSE).
Bundled third-party code retains its own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [licenses/](licenses/).
The OpenAI mark is a trademark and is not licensed by the project's Apache license.

OpenAI's [app-server auth guidance](https://learn.chatgpt.com/docs/app-server#auth-endpoints)
distinguishes local/open-source apps from commercial or hosted services. This app
is intended to remain free and operate on the user's device.
