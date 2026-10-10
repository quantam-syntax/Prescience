param(
    [string]$QairtSdkRoot = (Join-Path $PSScriptRoot "..\\.tools\\qairt\\2.50.40.260831"),
    [string]$CachedDlc = (Join-Path $PSScriptRoot "..\\models\\qnn-export\\sepformer-dlc\\sepformer-w8a16-sm8850-cached.dlc")
)

$ErrorActionPreference = "Stop"
$project = Join-Path $PSScriptRoot ".."
$jniDir = Join-Path $project "app\\src\\main\\jniLibs\\arm64-v8a"
$assetDir = Join-Path $project "app\\src\\main\\assets\\qairt"

if (-not (Test-Path $CachedDlc)) { throw "Cached SepFormer DLC not found: $CachedDlc" }
New-Item -ItemType Directory -Force -Path $jniDir, $assetDir | Out-Null

$androidLibs = Join-Path $QairtSdkRoot "lib\\aarch64-android"
$hexagonLibs = Join-Path $QairtSdkRoot "lib\\hexagon-v81\\unsigned"
$libraries = @(
    "libQairtSystem.so", "libQairtHtp.so", "libQairtHtpPrepare.so", "libQairtHtpV81Stub.so"
)
foreach ($name in $libraries) {
    Copy-Item -Force (Join-Path $androidLibs $name) (Join-Path $jniDir $name)
}
Copy-Item -Force (Join-Path $hexagonLibs "libQairtHtpV81.so") (Join-Path $jniDir "libQairtHtpV81.so")
Copy-Item -Force (Join-Path $hexagonLibs "libQairtHtpV81Skel.so") (Join-Path $jniDir "libQairtHtpV81Skel.so")
Copy-Item -Force $CachedDlc (Join-Path $assetDir "sepformer-w8a16-sm8850-cached.dlc")

Write-Host "Staged QAIRT HTP runtime and cached SepFormer model for arm64-v8a."
