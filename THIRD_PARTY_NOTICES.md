# Third-party notices

Original project code is Apache-2.0; that license does not replace the licenses
of dependencies or grant trademark rights.

## Native executable

The app bundles an on-device build of [OpenAI Codex](https://github.com/openai/codex),
commit `cc7ba33601286a751477832b01de7b6f8de0dd9c`, licensed under Apache-2.0.
Its upstream source and lockfile are unmodified. The build changes the target
platform and linker settings; it does not substitute an unrelated auth client.
The [upstream license](licenses/OpenAI-Codex-Apache-2.0.txt) and
[NOTICE](licenses/OpenAI-Codex-NOTICE.txt) are preserved. The NOTICE mentions
Ratatui-derived code; its [MIT license](licenses/Ratatui-MIT.txt) is included too.

The executable statically links OpenSSL 3.6.3, official commit
`aae016bfd52fcad2bc9657c2c782cfdf73b1ed5f`. Its
[Apache-2.0 license](licenses/OpenSSL-Apache-2.0.txt) is included separately.

The remaining Rust packages are inventoried in
[dependency-manifest.json](licenses/dependency-manifest.json), including versions,
license selections, and exact public source links. Full license-text variants and
copyright notices are bundled in
[third_party_licenses.html](app/src/main/assets/third_party_licenses.html), available
from the app's **Open-source licenses** menu. This includes MPL-2.0 dependencies;
their unmodified source is available at the exact-version links in the inventory.

This is a conservative target-specific app-server dependency graph, including
build dependencies, not a claim that every listed crate contributes linked code.
Regeneration instructions are in [licenses/README.md](licenses/README.md).
The Android app currently has no third-party Java/Android runtime library dependencies.

## Asset attribution

The small OpenAI Blossom mark in `app/src/main/res/drawable/ic_openai.xml`
uses the unmodified path and viewport from the OpenAI Developers site's logo
at https://developers.openai.com/learn/codex, retrieved 2026-10-10. It is shown
in white to identify the service whose usage is displayed. OpenAI owns the mark.
Brand guidelines: https://openai.com/brand/. This personal app is not an official
OpenAI application or an endorsement by OpenAI.
