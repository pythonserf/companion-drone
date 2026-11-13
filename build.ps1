# Builds the Companion Drone mod into build\companion-drone.jar
# Usage:  .\build.ps1            - build only
#         .\build.ps1 -Install   - build and copy the jar into the game's mods folder
#                                  (an old companion-drone-*.zip there is overwritten with the new build, never moved or deleted)
param([switch]$Install)
$ErrorActionPreference = "Stop"

$Root    = $PSScriptRoot
$Game    = "C:\Program Files (x86)\Steam\steamapps\common\Mindustry"
$GameJar = Join-Path $Game "jre\desktop.jar"
$ModsDir = Join-Path $Game "saves\mods"
$Jdk     = Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin"

$Build   = Join-Path $Root "build"
$Classes = Join-Path $Build "classes"
$OutJar  = Join-Path $Build "companion-drone.jar"

if(Test-Path $Build){ Remove-Item -Recurse -Force $Build }
New-Item -ItemType Directory -Force $Classes | Out-Null

$Sources = Get-ChildItem -Recurse (Join-Path $Root "src") -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' }
$ArgFile = Join-Path $Build "sources.txt"
[IO.File]::WriteAllLines($ArgFile, $Sources)

# the game's own jar is the library, so the API always matches the installed version
& (Join-Path $Jdk "javac.exe") --release 17 -encoding UTF-8 -nowarn -cp $GameJar -d $Classes "@$ArgFile"
if($LASTEXITCODE -ne 0){ throw "javac failed" }

& (Join-Path $Jdk "jar.exe") cf $OutJar -C $Classes . -C (Join-Path $Root "assets") .
if($LASTEXITCODE -ne 0){ throw "jar failed" }
Write-Host "Built: $OutJar"

if($Install){
    # saves\ is synced by Steam Cloud: files removed there come back on the next launch.
    # So old builds (companion-drone-*.zip) are overwritten in place with the new build, never moved or deleted.
    Copy-Item $OutJar (Join-Path $ModsDir "companion-drone.jar") -Force
    Write-Host "Installed: $(Join-Path $ModsDir 'companion-drone.jar')"
    Get-ChildItem $ModsDir -Filter "companion-drone-*.zip" | ForEach-Object {
        Copy-Item $OutJar $_.FullName -Force
        Write-Host "Overwrote old build with the new one: $($_.Name)"
    }
}
