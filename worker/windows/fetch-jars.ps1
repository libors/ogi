<#
.SYNOPSIS
Downloads robot jars on the Windows worker, for the foreign population. Nothing in them runs here.

.DESCRIPTION
Reads a tab-separated list, one jar per line: the jar's file name, then one or more URLs to try in order. Each jar
that is not in -Out yet is downloaded to <file>.part, kept only if it is a zip archive (Google Drive and others serve
HTML pages instead of missing files), and renamed. Appends one line per jar to -Result: the file, the status
("ok", "exists", "not a jar", "error: ..."), the URL used, the SHA-256 and the size.

The jars still have to pass the jar check and get into approved.txt (with their SHA-256) before a battle uses them;
queue.Executor copies only approved jars into its slots.

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File fetch-jars.ps1 -List C:\ogi\tmp\fetch.tsv `
    -Out C:\ogi\rumble\incoming -Result C:\ogi\tmp\fetch-result.tsv
#>
param(
    [Parameter(Mandatory = $true)] [string] $List,
    [Parameter(Mandatory = $true)] [string] $Out,
    [Parameter(Mandatory = $true)] [string] $Result
)
$ErrorActionPreference = "Stop"
# Windows PowerShell downloads much faster without the progress bar, and needs TLS 1.2 enabled by hand.
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

New-Item -ItemType Directory -Force $Out | Out-Null
foreach ($line in Get-Content $List) {
    if ($line.Trim() -eq "" -or $line.StartsWith("#")) { continue }
    $fields = $line -split "`t"
    $file = $fields[0]
    if ($file -notmatch '^[A-Za-z0-9_.$-]+\.jar$') {
        "$file`tbad name`t`t`t" | Add-Content $Result
        continue
    }
    $target = Join-Path $Out $file
    $status = "failed"
    $used = ""
    if (Test-Path -LiteralPath $target) {
        $status = "exists"
    } else {
        foreach ($url in $fields[1..($fields.Count - 1)]) {
            $part = "$target.part"
            try {
                Invoke-WebRequest -Uri $url -OutFile $part -UseBasicParsing -TimeoutSec 60 -MaximumRedirection 10
                $head = [byte[]]::new(2)
                $stream = [IO.File]::OpenRead($part)
                try { [void] $stream.Read($head, 0, 2) } finally { $stream.Close() }
                if ($head[0] -eq 0x50 -and $head[1] -eq 0x4B) {
                    Move-Item -LiteralPath $part -Destination $target -Force
                    $status = "ok"
                    $used = $url
                    break
                }
                $status = "not a jar"
                Remove-Item -LiteralPath $part -Force
            } catch {
                $status = "error: " + ($_.Exception.Message -replace "[`t`r`n]+", " ")
                Remove-Item -LiteralPath $part -Force -ErrorAction SilentlyContinue
            }
        }
    }
    $hash = ""
    $size = ""
    if (Test-Path -LiteralPath $target) {
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower()
        $size = (Get-Item -LiteralPath $target).Length
    }
    "$file`t$status`t$used`t$hash`t$size" | Add-Content $Result
}
