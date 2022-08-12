$codeSignerDN = 'ADUserSync Code Signer'
$codeSignerFile = 'ADUserSyncCodeSigner.cer'

# Check if code signer cert exists
$signerCert = Get-ChildItem -Path "Cert:\CurrentUser\My" | Where-Object { $_.Subject -match $codeSignerDN }
if (-not $signerCert) {
    Write-Host Signer cert not found, generating self-signed cert.
    New-SelfSignedCertificate -Type CodeSigning -Subject "CN=$codeSignerDN" -KeyAlgorithm RSA -KeyLength 4096 -HashAlgorithm sha256 -CertStoreLocation "Cert:\CurrentUser\My"
    Get-ChildItem -Path "Cert:\CurrentUser\My" | Where-Object { $_.Subject -match $codeSignerDN } | Export-Certificate -FilePath $codeSignerFile
    Import-Certificate -FilePath $codeSignerFile -CertStoreLocation "Cert:\CurrentUser\Root"
    Import-Certificate -FilePath $codeSignerFile -CertStoreLocation "Cert:\CurrentUser\TrustedPublisher"
    del $codeSignerFile
}