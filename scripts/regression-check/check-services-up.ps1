<#
.SYNOPSIS
  Stage 0 regression check — Step 1: verify all 5 core service ports are alive.

  Checks HTTP endpoints on:
    - web-service   → 8181  GET http://localhost:8181/api/web/strategies
    - market-data   → 8182  GET http://localhost:8182 (or /actuator/health)
    - indicator     → 8183  GET http://localhost:8183 (or /actuator/health)
    - backtest      → 8185  GET http://localhost:8185 (or /actuator/health)
    - web-app       → 3000  GET http://localhost:3000

  Returns a hashtable with status + details array.  This script never exits
  the process — the caller (run.ps1) decides overall PASS/FAIL.
#>

param(
    [int]$TimeoutSeconds = 10
)

$checkTimeoutMs = $TimeoutSeconds * 1000

$services = @(
    @{ Name = 'web-service';   Port = 8181;  Path = '/api/web/strategies' }
    @{ Name = 'market-data';   Port = 8182;  Path = '/' }
    @{ Name = 'indicator';     Port = 8183;  Path = '/' }
    @{ Name = 'backtest';      Port = 8185;  Path = '/' }
    @{ Name = 'web-app';       Port = 3000;  Path = '/' }
)

$details = @()

foreach ($svc in $services) {
    $url = "http://localhost:$($svc.Port)$($svc.Path)"
    $ok = $false
    $errMsg = $null
    $statusCode = $null

    try {
        $response = Invoke-WebRequest -Uri $url -Method GET -TimeoutSec $TimeoutSeconds -UseBasicParsing -ErrorAction Stop
        $statusCode = [int]$response.StatusCode
        # Any HTTP response (including 4xx/5xx) means the container is alive
        $ok = $true
    }
    catch {
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            # 4xx/5xx from a live container still counts as "service is up"
            $ok = $true
        }
        else {
            $errMsg = $_.Exception.Message
        }
    }

    if ($ok) {
        $details += @{
            service    = $svc.Name
            port       = $svc.Port
            status     = 'UP'
            http_code  = $statusCode
        }
    }
    else {
        $details += @{
            service    = $svc.Name
            port       = $svc.Port
            status     = 'DOWN'
            http_code  = $statusCode
            error      = $errMsg
        }
    }
}

$allUp = ($details | Where-Object { $_.status -eq 'DOWN' }).Count -eq 0

return @{
    status  = if ($allUp) { 'PASS' } else { 'FAIL' }
    details = $details
}