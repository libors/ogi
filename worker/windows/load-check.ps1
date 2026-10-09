<#
.SYNOPSIS
Load test of a native Windows worker: how many turns robots skip when several battles run at once.

.DESCRIPTION
Starts one RecordBattles process per entry of -Robots at the same time, each in its own Robocode home, against the
lab set, without debug mode, so that the CPU constant's turn limit applies. Prints, per process, the robot, its
skipped turns and the GC pauses inside battles (the engine's explicit full GCs between battles are left out).

Foreign jars in -Foreign are used only if their SHA-256 matches -Approved (the format of
.robocode/foreign/approved.txt: "<jar> <sha256> ...").

.EXAMPLE
powershell -ExecutionPolicy Bypass -File load-check.ps1 -Bundle C:\ogi\bundle -Foreign C:\ogi\rumble `
    -Approved C:\ogi\rumble\approved.txt -Robots "kc.mega.BeepBoop 2.0,cz.libors.Ogi 0.1" -JvmOptions "-Xmx256m"
#>
param(
    [Parameter(Mandatory = $true)] [string] $Bundle,
    # Comma-separated "name version" entries, one process each ("powershell -File" passes no arrays).
    [Parameter(Mandatory = $true)] [string] $Robots,
    [string] $Foreign = "",
    [string] $Approved = "",
    [string] $Java = "C:\ogi\jdk\bin\java.exe",
    [string] $JvmOptions = "",
    # Multiplies the CPU constant measured on this machine (a control: more time per turn).
    [double] $ConstantFactor = 1,
    # Measure the CPU constant with -JvmOptions first (the engine's benchmark runs under the same JIT settings),
    # instead of taking the one parallel-check.ps1 measured with the defaults.
    [switch] $MeasureConstant,
    [int] $Pairs = 2,
    [string] $Work = (Join-Path $env:TEMP "ogi-load-check")
)
$ErrorActionPreference = "Stop"
$list = @($Robots -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })

function Quote([string] $arg) {
    if ($arg -match '\s') { return '"' + $arg + '"' } else { return $arg }
}

if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory $Work | Out-Null

# Robocode with our jars, plus the approved foreign jars after their SHA-256 check.
$setup = Get-ChildItem (Join-Path $Bundle "worker\robocode-*-setup.jar") | Select-Object -First 1
$robocode = Join-Path $Work "robocode"
New-Item -ItemType Directory $robocode | Out-Null
tar -xf $setup.FullName -C $robocode
Copy-Item (Join-Path $Bundle "robots\*.jar") (Join-Path $robocode "robots")
if ($Foreign -ne "") {
    $expected = @{}
    Get-Content $Approved | Where-Object { $_ -match '^\s*[^#\s]' } | ForEach-Object {
        $fields = $_.Trim() -split '\s+'
        $expected[$fields[0]] = $fields[1].ToLower()
    }
    foreach ($jar in Get-ChildItem (Join-Path $Foreign "*.jar")) {
        $hash = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower()
        if ($expected[$jar.Name] -ne $hash) { throw "Not approved or changed: $($jar.Name) $hash" }
        Copy-Item $jar.FullName (Join-Path $robocode "robots")
    }
}

# The CPU constant: measured once on this machine (kept in the bundle's state, or measured now by a short run).
$state = Join-Path $Work "state.properties"
$measured = Join-Path $env:TEMP "ogi-parallel-check\home-single\config\robocode.properties"
if ($MeasureConstant) {
    # One short battle in a fresh home: the engine measures the constant at its start, under these JVM options.
    $probeHome = Join-Path $Work "home-constant"
    Copy-Item -Recurse $robocode $probeHome
    $jvm = @("-Xmx1g") + ($JvmOptions -split ' ' | Where-Object { $_ -ne "" }) + @(
        "-XX:+IgnoreUnrecognizedVMOptions", "-Djava.security.manager=allow",
        "-cp", ((Join-Path $Bundle "lib\harness.jar") + ";" + (Join-Path $probeHome "libs\*")))
    $arguments = @("cz.libors.harness.compare.RecordBattles", $probeHome, "robot=cz.libors.Ogi", "versions=0.1",
        "opponents=$(Join-Path $Bundle "harness\opponents\lab-1v1.txt")", "out=$(Join-Path $Work "out-constant")",
        "only=sample.SittingDuck", "pairs=1", "rounds=1", "keys=none")
    $p = Start-Process -FilePath $Java -ArgumentList (($jvm + $arguments) | ForEach-Object { Quote $_ }) `
        -WorkingDirectory $probeHome -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $Work "constant.txt") `
        -RedirectStandardError (Join-Path $Work "constant.err")
    $p.WaitForExit()
    $measured = Join-Path $probeHome "config\robocode.properties"
}
if (-not (Test-Path $measured)) { throw "Run parallel-check.ps1 once first; it measures the CPU constant." }
$constant = [long]((Get-Content $measured | Where-Object { $_ -match 'cpu.constant' }) -replace '.*=', '')
"robocode.cpu.constant=" + [long]($constant * $ConstantFactor) | Set-Content $state -Encoding ascii

$opponents = Join-Path $Bundle "harness\opponents\lab-1v1.txt"
$processes = @()
$watch = [Diagnostics.Stopwatch]::StartNew()
for ($k = 0; $k -lt $list.Count; $k++) {
    $robocodeHome = Join-Path $Work "home$k"
    Copy-Item -Recurse $robocode $robocodeHome
    New-Item -ItemType Directory -Force (Join-Path $robocodeHome "config") | Out-Null
    Copy-Item $state (Join-Path $robocodeHome "config\robocode.properties")
    $name, $version = $list[$k] -split ' ', 2
    # Relative to the home: -Xlog splits its arguments at colons, so a path with a drive letter would break it.
    $jvm = @("-Xmx1g") + ($JvmOptions -split ' ' | Where-Object { $_ -ne "" }) + @(
        "-Xlog:gc:file=gc.log:uptime",
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "-Djava.security.manager=allow",
        "-cp", ((Join-Path $Bundle "lib\harness.jar") + ";" + (Join-Path $robocodeHome "libs\*")))
    $arguments = @("cz.libors.harness.compare.RecordBattles", $robocodeHome, "robot=$name", "versions=$version",
        "opponents=$opponents", "out=$(Join-Path $Work "out$k")", "pairs=$Pairs", "seed=$(500 + $k)", "keys=none")
    $all = ($jvm + $arguments) | ForEach-Object { Quote $_ }
    $processes += Start-Process -FilePath $Java -ArgumentList $all -WorkingDirectory $robocodeHome -NoNewWindow `
        -PassThru -RedirectStandardOutput (Join-Path $Work "log$k.txt") -RedirectStandardError (Join-Path $Work "err$k.txt")
}
$processes | ForEach-Object { $_.WaitForExit() }
"{0} processes, JVM options '{1}', CPU constant {2:N0} x{3}: {4:N0} s" -f $list.Count, $JvmOptions, $constant,
    $ConstantFactor, $watch.Elapsed.TotalSeconds

for ($k = 0; $k -lt $list.Count; $k++) {
    $index = Get-ChildItem (Join-Path $Work "out$k") -Recurse -Filter battles.tsv | Select-Object -First 1
    $rows = if ($index) { Get-Content $index.FullName | Select-Object -Skip 1 } else { @() }
    $skipped = ($rows | ForEach-Object { [int](($_ -split "`t")[6]) } | Measure-Object -Sum).Sum
    $problems = ($rows | ForEach-Object { [int](($_ -split "`t")[7]) } | Measure-Object -Sum).Sum
    $pauses = Get-Content (Join-Path $Work "home$k\gc.log") | Where-Object { $_ -match 'Pause' -and $_ -notmatch 'System.gc' } |
        ForEach-Object { if ($_ -match '([0-9.]+)ms$') { [double]$Matches[1] } }
    $stats = $pauses | Measure-Object -Sum -Maximum
    $over = ($pauses | Where-Object { $_ -gt 2 } | Measure-Object).Count
    "{0,-24} battles {1,3}  skipped {2,5}  problems {3,2}  | GC pauses in battles {4,5}, sum {5,7:N0} ms, max {6,6:N1} ms, over 2 ms {7,5}" -f `
        $list[$k], $rows.Count, $skipped, $problems, $stats.Count, $stats.Sum, $stats.Maximum, $over
}
