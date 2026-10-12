# Dependency notices

The project license is Apache-2.0. The native executable uses the pinned official
Codex source, its Rust dependency graph, and statically linked OpenSSL 3.6.3.
Third-party licenses are preserved separately and are not replaced by the project's
license. The upstream Codex NOTICE is retained verbatim.

`dependency-manifest.json` records package names, versions, declared/selected
licenses, and exact public source links. It contains no local filesystem paths.
The inventory covers the app-server's target-specific normal/build dependency
graph; it is conservative, not a list of individual functions linked into the APK.
MPL-2.0 dependency source is available at the listed exact-version URLs.

Full Rust dependency notices plus the project, Codex, and OpenSSL licenses are
bundled in `app/src/main/assets/third_party_licenses.html` and accessible through
the app's **Open-source licenses** menu item. The OpenAI mark is a trademark,
outside the scope of the project's code license.

## Regeneration

Prepare the pinned checkout described in `BUILDING.md`, then install the upstream
[cargo-about](https://github.com/EmbarkStudios/cargo-about) **0.9.2** tool.
If using a release binary, verify its published checksum before running it.

```text
python scripts/generate-notices.py --cargo-about PATH_TO_CARGO_ABOUT
```

Generation uses `--locked --fail`, the ARM64 Android target, and the explicit
license choices in `about.toml`. It fails on unresolved licenses instead of silently
omitting them. Keep native dependency changes and regenerated notices together.
The raw tool output stays under ignored `.probe/` because it includes local paths.
