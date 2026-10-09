<#
.SYNOPSIS
Checks a Windows machine as a native harness worker, with trusted code only: our harness and bots, the sample bots
and the probes; no foreign jar.

.DESCRIPTION
Unpacks Robocode from the bundle, runs the skeleton check once (it also measures the CPU constant), then runs
-Runs comparisons of the bundle's baseline and candidate against the lab set at the same time, each in its own
Robocode home, as worker/remote.sh runs them in containers. Prints the skeleton check's lines and each comparison's
health line: skipped turns and problems must stay near zero (docs/build-and-run.md, "Remote worker").

.EXAMPLE
powershell -ExecutionPolicy Bypass -File parallel-check.ps1 -Bundle C:\ogi-test\bundle
#>
param(
    # A copy of the worker bundle (./gradlew workerBundle), e.g. from \\wsl.localhost\ogi\home\<user>\ogi\bundle.
    [Parameter(Mandatory = $true)] [string] $Bundle,
    # A Java 17 runtime.
    [string] $Java = "java",
    [int] $Runs = 4,
    [string] $Baseline = "0a6ea62",
    [string] $Candidate = "0.1",
    [string] $Work = (Join-Path $env:TEMP "ogi-parallel-check")
)
$ErrorActionPreference = "Stop"

function Quote([string] $arg) {
    if ($arg -match '\s') { return '"' + $arg + '"' } else { return $arg }
}

# Starts one harness main class in a Robocode home; returns the process.
function Start-Harness([string] $robocodeHome, [string] $log, [string[]] $mainAndArguments) {
    $jvm = @(
        "-Xmx1g",
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "-Djava.security.manager=allow",
        "-cp", ((Join-Path $Bundle "lib\harness.jar") + ";" + (Join-Path $robocodeHome "libs\*"))
    )
    $all = ($jvm + $mainAndArguments) | ForEach-Object { Quote $_ }
    return Start-Process -FilePath $Java -ArgumentList $all -WorkingDirectory $robocodeHome -NoNewWindow -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError ($log + ".err")
}

& $Java -version
if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory $Work | Out-Null

# Robocode, unpacked from the setup jar in the bundle, plus our jars.
$setup = Get-ChildItem (Join-Path $Bundle "worker\robocode-*-setup.jar") | Select-Object -First 1
Write-Host ("Robocode setup jar " + $setup.Name + ", SHA-256 " + (Get-FileHash $setup.FullName -Algorithm SHA256).Hash)
$robocode = Join-Path $Work "robocode"
New-Item -ItemType Directory $robocode | Out-Null
tar -xf $setup.FullName -C $robocode
Copy-Item (Join-Path $Bundle "robots\*.jar") (Join-Path $robocode "robots")

# One skeleton check alone: the speed of a single run, and the CPU constant, which every home then shares.
$single = Join-Path $Work "home-single"
Copy-Item -Recurse $robocode $single
$watch = [Diagnostics.Stopwatch]::StartNew()
$p = Start-Harness $single (Join-Path $Work "skeleton.txt") @("cz.libors.harness.SkeletonCheck", $single,
    "cz.libors.Ogi $Candidate", "cz.libors.OgiTeam $Candidate", (Join-Path $Work "skeleton-check.json"))
$p.WaitForExit()
Write-Host ("Skeleton check alone: {0:N0} s" -f $watch.Elapsed.TotalSeconds)
Get-Content (Join-Path $Work "skeleton.txt") | Where-Object { $_ -match '^(OK|FAIL)|checks passed' }
$config = Join-Path $single "config\robocode.properties"
Get-Content $config | Where-Object { $_ -match 'cpu.constant' }

# The parallel comparisons.
$opponents = Join-Path $Bundle "harness\opponents\lab-1v1.txt"
$processes = @()
$watch = [Diagnostics.Stopwatch]::StartNew()
for ($k = 0; $k -lt $Runs; $k++) {
    $robocodeHome = Join-Path $Work "home$k"
    Copy-Item -Recurse $robocode $robocodeHome
    New-Item -ItemType Directory -Force (Join-Path $robocodeHome "config") | Out-Null
    Copy-Item $config (Join-Path $robocodeHome "config")
    $processes += Start-Harness $robocodeHome (Join-Path $Work "compare$k.txt") @(
        "cz.libors.harness.compare.Compare", $robocodeHome, "robot=cz.libors.Ogi", "team=cz.libors.OgiTeam",
        "baseline=$Baseline", "candidate=$Candidate", "opponents=$opponents", "out=$(Join-Path $Work "out$k")",
        "pairsPerOpponent=2", "metric=hits", "seed=$(200 + $k)")
}
$processes | ForEach-Object { $_.WaitForExit() }
Write-Host ("{0} comparisons at once, {1} battles: {2:N0} s" -f $Runs, (60 * $Runs), $watch.Elapsed.TotalSeconds)
for ($k = 0; $k -lt $Runs; $k++) {
    Get-Content (Join-Path $Work "compare$k.txt") | Where-Object { $_ -match '^health' }
}
