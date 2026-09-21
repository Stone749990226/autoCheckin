param([string]$JdkPath = '', [switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkPath) {
    $candidates = @($env:JAVA_HOME, "$env:USERPROFILE\.jdks\ms-17.0.16")
    $candidates += @(Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath "$candidate\release")) {
            $release = Get-Content -LiteralPath "$candidate\release" -Raw
            if ($release -match 'JAVA_VERSION="17\.') { $JdkPath = $candidate; break }
        }
    }
}
if (-not $JdkPath -or -not (Test-Path -LiteralPath "$JdkPath\bin\java.exe")) {
    throw 'JDK 17 not found. Run: .\scripts\build.ps1 -JdkPath C:\path\to\jdk17'
}
$env:JAVA_HOME = $JdkPath
Push-Location $projectRoot
try {
    if ($SkipChecks) { & .\gradlew.bat :app:assembleDebug --console=plain }
    else { & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
