$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "ADB not found: $adb" }
& $adb devices -l
$serials = @(& $adb devices | Select-String '^\S+\s+device$')
if ($serials.Count -ne 1) { throw 'Connect exactly one authorized USB debugging device.' }
foreach ($property in @('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.version.opporom', 'ro.build.version.oplusrom', 'ro.vivo.os.version')) {
    Write-Output $property
    & $adb shell getprop $property
}
& $adb shell wm size
& $adb shell wm density
& $adb shell dumpsys package com.alibaba.android.rimet | Select-String 'versionName=|versionCode='
