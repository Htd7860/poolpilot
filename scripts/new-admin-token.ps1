$ErrorActionPreference = 'Stop'
$poolTokenBytes = New-Object byte[] 32
$poolTokenRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $poolTokenRandom.GetBytes($poolTokenBytes)
    [Convert]::ToBase64String($poolTokenBytes)
} finally {
    $poolTokenRandom.Dispose()
}
