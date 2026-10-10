$ErrorActionPreference = "Stop"

$sdkRoot = @($env:QAIRT_SDK_ROOT, $env:QNN_SDK_ROOT) | Where-Object { $_ } | Select-Object -First 1
if (-not $sdkRoot) {
    $localSdk = Get-ChildItem (Join-Path $PSScriptRoot "..\.tools\qairt") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($localSdk) { $sdkRoot = $localSdk.FullName }
}

$hostBin = if ($sdkRoot) { Join-Path $sdkRoot "bin\x86_64-windows-msvc" } else { $null }
$requiredCommands = @("adb", "qairt-converter", "qairt-quantizer", "qairt-net-run")
$commandState = foreach ($name in $requiredCommands) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    $sdkCandidates = if ($hostBin) { @((Join-Path $hostBin $name), (Join-Path $hostBin "$name.exe")) } else { @() }
    $sdkCandidate = $sdkCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    $resolved = if ($command) { $command.Source } elseif ($sdkCandidate) { $sdkCandidate } else { $null }
    [pscustomobject]@{
        Component = $name
        Available = $null -ne $resolved
        Location = if ($resolved) { $resolved } else { "not found" }
    }
}

$commandState | Format-Table -AutoSize

if ($sdkRoot) {
    Write-Host "QAIRT/QNN SDK root: $sdkRoot"
} else {
    Write-Host "QAIRT/QNN SDK root: not configured"
}

$device = adb devices | Select-String "\tdevice$" | Select-Object -First 1
if ($device) {
    Write-Host "Connected Android device: $($device.Line.Split("`t")[0])"
    Write-Host "SoC model: $(adb shell getprop ro.soc.model)"
    Write-Host "Android ABI: $(adb shell getprop ro.product.cpu.abi)"
    Write-Host "Android release: $(adb shell getprop ro.build.version.release)"
} else {
    Write-Host "Connected Android device: none"
}

$separationCheckpoint = Join-Path $PSScriptRoot "..\models\separation\speechbrain-sepformer-wsj02mix\masknet.ckpt"
$speakerModel = Join-Path $PSScriptRoot "..\app\src\main\assets\3dspeaker_eres2net_en_voxceleb_16k.onnx"
foreach ($asset in @($separationCheckpoint, $speakerModel)) {
    if (Test-Path -LiteralPath $asset) {
        $file = Get-Item -LiteralPath $asset
        Write-Host "Model present: $($file.Name) ($([math]::Round($file.Length / 1MB, 1)) MB)"
    } else {
        Write-Host "Model missing: $asset"
    }
}

if (-not $sdkRoot -or ($commandState | Where-Object { -not $_.Available })) {
    Write-Host "BLOCKED: Install Qualcomm AI Runtime SDK or extract it under .tools/qairt before conversion."
    exit 2
}

Write-Host "READY: QNN host tools and an Android target are available."
