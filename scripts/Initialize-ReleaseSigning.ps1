#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Create,
    [Parameter(Mandatory)][string]$BackupDirectory
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$backupRoot = [IO.Path]::GetFullPath($BackupDirectory).TrimEnd('\', '/')
$projectRoot = [IO.Path]::GetFullPath($project).TrimEnd('\', '/')
if ($backupRoot.Equals($projectRoot, [StringComparison]::OrdinalIgnoreCase) -or $backupRoot.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep the signing backup outside the source project.' }
$privateDir = Join-Path $project 'signing'
$keystorePath = Join-Path $privateDir 'baoxiao-release.p12'
$passwordPath = Join-Path $privateDir 'keystore-password.txt'
$pinPath = Join-Path $project 'config/release-signing.json'
$utf8 = [Text.UTF8Encoding]::new($false)
$existing = @($keystorePath, $passwordPath, $pinPath) | Where-Object { Test-Path -LiteralPath $_ }
if ($existing.Count -eq 0) {
    if (-not $Create) { throw 'No signing key. Restore its private backup; use -Create only for an explicitly approved first migration.' }
    $ignore = Get-Content -LiteralPath (Join-Path $project '.gitignore') -Raw
    if ($ignore -notmatch '(?m)^/signing/\r?$') { throw 'The private signing directory must be ignored by Git first.' }
    New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
    New-Item -ItemType Directory -Path (Split-Path $pinPath -Parent) -Force | Out-Null
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $rsa = [Security.Cryptography.RSA]::Create(3072)
    try {
        $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
            'CN=BaoXiao Release,O=BaoXiao,C=CN', $rsa,
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
        $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new([Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature, $true))
        $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1), [DateTimeOffset]::UtcNow.AddYears(40))
        try {
            $fingerprint = $certificate.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
            [IO.File]::WriteAllBytes($keystorePath, $certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12, $password))
            [IO.File]::WriteAllText($passwordPath, $password, $utf8)
            $pin = [ordered]@{
                packageName = 'cn.baoxiao.ledger'
                certificateSha256 = $fingerprint
                certificateSubject = $certificate.Subject
                certificateExpiresUtc = $certificate.NotAfter.ToUniversalTime().ToString('o')
                minimumVersionCode = 4
            }
            [IO.File]::WriteAllText($pinPath, (($pin | ConvertTo-Json).Replace("`r`n", "`n")) + "`n", $utf8)
        } finally { $certificate.Dispose() }
    } finally { $rsa.Dispose(); $password = $null }
} elseif ($existing.Count -ne 3) {
    throw 'Signing files are incomplete. Restore the original private backup; do not generate a replacement key.'
}
$pin = Get-Content -LiteralPath $pinPath -Raw | ConvertFrom-Json
$password = [IO.File]::ReadAllText($passwordPath).TrimEnd("`r", "`n")
$certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($keystorePath, $password, [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
try {
    if (-not $certificate.HasPrivateKey) { throw 'The keystore does not contain a private key.' }
    $actual = $certificate.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
    if ($actual -ne $pin.certificateSha256) { throw 'The private key does not match the pinned release certificate.' }
} finally { $certificate.Dispose(); $password = $null }
$instructions = @'
PRIVATE BAOXIAO SIGNING BACKUP - DO NOT UPLOAD OR COMMIT

Keep this archive in a separate private location. It contains the permanent
PKCS12 signing key and its password. Anyone with this archive can sign updates.

To restore after moving the source project:
1. Restore baoxiao-release.p12 and keystore-password.txt into project/signing/.
2. Keep the public config/release-signing.json file with the source.
3. Run scripts/Initialize-ReleaseSigning.ps1 with -BackupDirectory to verify.
4. Use scripts/Sign-Apk.ps1 for future unsigned release builds.

Do not create a new key for future versions. Increase versionCode while keeping
package cn.baoxiao.ledger and this exact certificate. The first 0.1.3 migration
requires an old-app full data backup and reinstall; later updates use this key.
'@
$readmePath = Join-Path $privateDir 'README-PRIVATE.txt'
[IO.File]::WriteAllText($readmePath, $instructions, $utf8)
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$backupPath = Join-Path $backupRoot ('BaoXiao-signing-' + $pin.certificateSha256.Substring(0, 12) + '.zip')
if (-not (Test-Path -LiteralPath $backupPath)) {
    Compress-Archive -LiteralPath @($keystorePath, $passwordPath, $pinPath, $readmePath) -DestinationPath $backupPath
}
$archive = [IO.Compression.ZipFile]::OpenRead($backupPath)
try {
    foreach ($filePath in @($keystorePath, $passwordPath, $pinPath, $readmePath)) {
        $entry = $archive.GetEntry([IO.Path]::GetFileName($filePath))
        if ($null -eq $entry) { throw 'Signing backup is incomplete.' }
        if ($filePath -eq $pinPath) {
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $backupPin = $reader.ReadToEnd() | ConvertFrom-Json }
            finally { $reader.Dispose() }
            if ($backupPin.certificateSha256 -ne $pin.certificateSha256 -or $backupPin.packageName -ne $pin.packageName) { throw 'Signing backup contains a different public certificate.' }
            continue
        }
        if ($filePath -eq $readmePath) { continue }
        $stream = $entry.Open()
        try { $entryHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
        finally { $stream.Dispose() }
        if ($entryHash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $filePath).Hash) { throw 'Signing backup does not match the live signing files.' }
    }
} finally { $archive.Dispose() }
Write-Output ('Release certificate SHA256: ' + $pin.certificateSha256)
Write-Output ('Verified private backup: ' + $backupPath)
