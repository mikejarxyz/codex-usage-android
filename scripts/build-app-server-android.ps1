param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [int]$Jobs = 4
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sourceRoot = Join-Path $projectRoot '.probe\codex\codex-rs'
$opensslRoot = Join-Path $projectRoot '.probe\openssl-android'
$ndkBin = Join-Path $SdkRoot 'ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin'
$expectedCommit = 'cc7ba33601286a751477832b01de7b6f8de0dd9c'

foreach ($requiredPath in @("$sourceRoot\Cargo.toml", "$opensslRoot\lib\libssl.a",
        "$opensslRoot\lib\libcrypto.a", "$ndkBin\aarch64-linux-android26-clang.cmd")) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing native build prerequisite: $requiredPath. See DIAGNOSTICS.md."
    }
}
$actualCommit = & git -C $sourceRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actualCommit -ne $expectedCommit) {
    throw 'The Codex source does not match the recorded probe commit.'
}

$buildEnvironment = @{
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$ndkBin\aarch64-linux-android26-clang.cmd"
    CC_aarch64_linux_android = "$ndkBin\aarch64-linux-android26-clang.cmd"
    CXX_aarch64_linux_android = "$ndkBin\aarch64-linux-android26-clang++.cmd"
    AR_aarch64_linux_android = "$ndkBin\llvm-ar.exe"
    AARCH64_LINUX_ANDROID_OPENSSL_DIR = $opensslRoot
    AARCH64_LINUX_ANDROID_OPENSSL_STATIC = '1'
    CARGO_PROFILE_DEV_DEBUG = '0'
    CARGO_PROFILE_DEV_STRIP = 'debuginfo'
}
$savedEnvironment = @{}
try {
    foreach ($name in $buildEnvironment.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $buildEnvironment[$name], 'Process')
    }
    Push-Location $sourceRoot
    try {
        # Link the final executable for both 4 KB and 16 KB Android kernels.
        # Apply final-link flags without invalidating every already-built dependency.
        & cargo +1.98.0 rustc --locked -p codex-app-server --bin codex-app-server `
            --target aarch64-linux-android -j $Jobs -- `
            -C link-arg=-Wl,-z,max-page-size=16384 `
            -C link-arg=-Wl,-z,common-page-size=16384
        if ($LASTEXITCODE -ne 0) { throw "Native build failed (exit $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
    $binary = Join-Path $sourceRoot 'target\aarch64-linux-android\debug\codex-app-server'
    $packagedDirectory = Join-Path $projectRoot '.probe\packaged-native\arm64-v8a'
    New-Item -ItemType Directory -Force -Path $packagedDirectory | Out-Null
    Copy-Item -LiteralPath $binary -Destination "$packagedDirectory\libcodex_app_server.so"
    Write-Output "Packaged Android executable: $packagedDirectory\libcodex_app_server.so"
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
