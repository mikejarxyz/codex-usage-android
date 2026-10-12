#!/usr/bin/env bash
# Build from pinned public inputs. No account data or host helper is involved.
set -euo pipefail
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--prepare-sources" ) ]]; then
  echo 'Usage: build-native.sh [--prepare-sources]' >&2; exit 1
fi
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
task_jobs="${BUILD_JOBS:-2}"
task_ndk_version=27.0.12077973
task_codex_commit=cc7ba33601286a751477832b01de7b6f8de0dd9c
task_openssl_commit=aae016bfd52fcad2bc9657c2c782cfdf73b1ed5f
task_rust=1.98.0
task_work="$task_root/.probe"
case "$(uname -s)" in
  Linux) task_host=linux-x86_64 ;;
  Darwin) task_host=darwin-x86_64 ;;
  *) echo 'Use this helper on Linux/macOS; the PowerShell helper supports prepared Windows dependencies.' >&2; exit 1 ;;
esac
task_ndk="$task_sdk/ndk/$task_ndk_version"
task_tools="$task_ndk/toolchains/llvm/prebuilt/$task_host/bin"
command -v git >/dev/null || { echo 'Missing prerequisite: git' >&2; exit 1; }
if [[ "${1:-}" != "--prepare-sources" ]]; then
  if [[ -z "$task_sdk" ]]; then echo 'Set ANDROID_HOME to an Android SDK with NDK 27.0.12077973.' >&2; exit 1; fi
  test -x "$task_tools/aarch64-linux-android26-clang" || { echo 'Install the pinned Android NDK first.' >&2; exit 1; }
  for tool in perl make cargo rustup; do command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }; done
fi
mkdir -p "$task_work"
pin_checkout() {
  local location="$1" remote="$2" commit="$3"
  if [[ ! -d "$location/.git" ]]; then
    if [[ -e "$location" ]]; then echo "Refusing to overwrite existing non-Git path: $location" >&2; exit 1; fi
    git init -q "$location"
    git -C "$location" remote add origin "$remote"
  fi
  if ! git -C "$location" cat-file -e "$commit^{commit}" 2>/dev/null; then
    git -C "$location" fetch --depth 1 origin "$commit"
  fi
  git -C "$location" diff --quiet && git -C "$location" diff --cached --quiet || { echo 'Pinned checkout contains tracked changes.' >&2; exit 1; }
  git -C "$location" checkout --detach -q "$commit"
  [[ "$(git -C "$location" rev-parse HEAD)" == "$commit" ]]
}
pin_checkout "$task_work/codex" https://github.com/openai/codex.git "$task_codex_commit"
pin_checkout "$task_work/openssl-official" https://github.com/openssl/openssl.git "$task_openssl_commit"
if [[ "${1:-}" == "--prepare-sources" ]]; then exit 0; fi
task_openssl="$task_work/openssl-android"
task_stamp="$task_openssl_commit/$task_ndk_version/android26"
if [[ ! -f "$task_openssl/lib/libssl.a" || ! -f "$task_openssl/lib/libcrypto.a" || ! -f "$task_openssl/.build-inputs" || "$(cat "$task_openssl/.build-inputs" 2>/dev/null)" != "$task_stamp" ]]; then
  (
    cd "$task_work/openssl-official"
    export ANDROID_NDK_ROOT="$task_ndk"
    export PATH="$task_tools:$PATH"
    perl Configure android-arm64 -D__ANDROID_API__=26 no-shared no-tests \
      --prefix="$task_openssl" --openssldir=/data/local/tmp/codex-usage-openssl
    make -j"$task_jobs" build_libs
    make install_dev
    printf '%s\n' "$task_stamp" > "$task_openssl/.build-inputs"
  )
fi
rustup toolchain install "$task_rust" --profile minimal
rustup target add aarch64-linux-android --toolchain "$task_rust"
(
  cd "$task_work/codex/codex-rs"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$task_tools/aarch64-linux-android26-clang"
  export CC_aarch64_linux_android="$task_tools/aarch64-linux-android26-clang"
  export CXX_aarch64_linux_android="$task_tools/aarch64-linux-android26-clang++"
  export AR_aarch64_linux_android="$task_tools/llvm-ar"
  export AARCH64_LINUX_ANDROID_OPENSSL_DIR="$task_openssl"
  export AARCH64_LINUX_ANDROID_OPENSSL_STATIC=1
  export CARGO_PROFILE_DEV_DEBUG=0
  export CARGO_PROFILE_DEV_STRIP=debuginfo
  cargo +"$task_rust" rustc --locked -p codex-app-server --bin codex-app-server \
    --target aarch64-linux-android -j "$task_jobs" -- \
    -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384
)
mkdir -p "$task_work/packaged-native/arm64-v8a"
cp "$task_work/codex/codex-rs/target/aarch64-linux-android/debug/codex-app-server" \
  "$task_work/packaged-native/arm64-v8a/libcodex_app_server.so"
echo 'Native ARM64 executable prepared; regenerate notices before packaging.'
