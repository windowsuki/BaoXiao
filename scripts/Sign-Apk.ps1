#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$UnsignedApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [string]$ToolsDirectory
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
if (-not $ToolsDirectory) { $ToolsDirectory = Join-Path $project 'app/build/signing-tools' }
$java = @(Get-ChildItem -LiteralPath $ToolsDirectory -Filter java.exe -File -Recurse)
if ($java.Count -ne 1) { throw 'Expected exactly one portable Java runtime in the signing tools directory.' }
$jar = Join-Path $ToolsDirectory 'apksigner.jar'
$jarHashPath = Join-Path $ToolsDirectory 'apksigner.jar.sha256'
if (-not (Test-Path -LiteralPath $jarHashPath)) { throw 'Missing verified apksigner tool checksum.' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash.ToLowerInvariant() -ne ([IO.File]::ReadAllText($jarHashPath).Trim().ToLowerInvariant())) { throw 'apksigner tool checksum mismatch.' }
$keystorePath = Join-Path $project 'signing/baoxiao-release.p12'
$passwordPath = Join-Path $project 'signing/keystore-password.txt'
$pin = Get-Content -LiteralPath (Join-Path $project 'config/release-signing.json') -Raw | ConvertFrom-Json
if (-not (Test-Path -LiteralPath $keystorePath) -or -not (Test-Path -LiteralPath $passwordPath)) { throw 'Restore the permanent signing backup first. A replacement key must not be generated.' }
$password = [IO.File]::ReadAllText($passwordPath).TrimEnd("`r", "`n")
$certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($keystorePath, $password, [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
try {
    if (-not $certificate.HasPrivateKey -or $certificate.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant() -ne $pin.certificateSha256) { throw 'Permanent signing key does not match the pinned certificate.' }
} finally { $certificate.Dispose(); $password = $null }
$source = (Resolve-Path -LiteralPath $UnsignedApk).Path
$info = Get-Content -LiteralPath (Join-Path (Split-Path $source -Parent) 'build-info.json') -Raw | ConvertFrom-Json
if ($info.packageName -ne $pin.packageName -or [long]$info.versionCode -lt [long]$pin.minimumVersionCode) { throw 'APK package or version does not match the permanent signing configuration.' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant() -ne $info.unsignedApkSha256) { throw 'Unsigned APK does not match its validated build metadata.' }
$output = [IO.Path]::GetFullPath($OutputApk)
if ($source -eq $output) { throw 'Keep the unsigned input separate from the signed output.' }
New-Item -ItemType Directory -Path (Split-Path $output -Parent) -Force | Out-Null
$pending = $output + '.pending.apk'
& $java[0].FullName -jar $jar sign --ks-type PKCS12 --ks $keystorePath --ks-pass ('file:' + $passwordPath) --key-pass ('file:' + $passwordPath) --min-sdk-version 26 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $pending $source
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
$verification = & $java[0].FullName -jar $jar verify --verbose --print-certs $pending 2>&1
if ($LASTEXITCODE -ne 0) { throw ('APK verification failed: ' + ($verification -join "`n")) }
$fingerprints = @($verification | ForEach-Object { if ($_ -match '^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$') { $Matches[1].ToLowerInvariant() } })
if ($fingerprints.Count -ne 1 -or $fingerprints[0] -ne $pin.certificateSha256) { throw 'Signed APK certificate does not match the pinned release key.' }
Move-Item -LiteralPath $pending -Destination $output -Force
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash.ToLowerInvariant()
$report = @(('Pinned certificate SHA256: ' + $pin.certificateSha256), ('Package: ' + $info.packageName), ('Version code: ' + $info.versionCode), ('Version name: ' + $info.versionName), ('Source commit: ' + $info.sourceCommit)) + $verification + @('APK SHA256: ' + $hash)
[IO.File]::WriteAllLines($output + '.verification.txt', $report, [Text.UTF8Encoding]::new($false))
Write-Output ('Signed and verified: ' + $output)
Write-Output ('Certificate SHA256: ' + $pin.certificateSha256)
Write-Output ('APK SHA256: ' + $hash)
