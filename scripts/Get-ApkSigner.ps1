param([Parameter(Mandatory)][string[]]$Paths)
foreach ($apkPath in $Paths) {
    $bytes = [IO.File]::ReadAllBytes((Resolve-Path $apkPath))
    $eocd = $bytes.Length - 22
    while ($eocd -ge [Math]::Max(0, $bytes.Length - 65557)) {
        if ([BitConverter]::ToUInt32($bytes, $eocd) -eq 0x06054b50 -and $eocd + 22 + [BitConverter]::ToUInt16($bytes, $eocd + 20) -eq $bytes.Length) { break }
        $eocd--
    }
    if ($eocd -lt 0) { throw 'ZIP end record missing' }
    $central = [BitConverter]::ToUInt32($bytes, $eocd + 16)
    if ([Text.Encoding]::ASCII.GetString($bytes, $central - 16, 16) -ne 'APK Sig Block 42') { throw 'APK signing block missing' }
    $blockSize = [BitConverter]::ToUInt64($bytes, $central - 24)
    $offset = [int]($central - $blockSize)
    $found = $false
    while ($offset -lt $central - 24) {
        $pairSize = [BitConverter]::ToUInt64($bytes, $offset)
        $pairId = [BitConverter]::ToUInt32($bytes, $offset + 8)
        if ($pairId -eq 0x7109871a) {
            $signedData = $offset + 24
            $digestsSize = [BitConverter]::ToUInt32($bytes, $signedData)
            $certificate = $signedData + 4 + $digestsSize + 4
            $size = [BitConverter]::ToUInt32($bytes, $certificate)
            $raw = [byte[]]$bytes[($certificate + 4)..($certificate + 3 + $size)]
            $cert = [Security.Cryptography.X509Certificates.X509Certificate2]::new($raw)
            [PSCustomObject]@{Path=$apkPath; Subject=$cert.Subject; Serial=$cert.SerialNumber; CertificateSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw))}
            $cert.Dispose()
            $found = $true
            break
        }
        $offset += [int]$pairSize + 8
    }
    if (!$found) { throw 'APK v2 signature missing' }
}
