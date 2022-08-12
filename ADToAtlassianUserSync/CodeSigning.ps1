$codeSignerDN = 'ADUserSync Code Signer'
$codeSignerFile = 'ADUserSync Code Signer.cer'

# Check if code signer cert exists
$signerCert = Get-ChildItem -Path "Cert:\CurrentUser\My" | Where-Object { $_.Subject -match $codeSignerDN }
Set-AuthenticodeSignature -File ADUserSync.ps1 -Cert $signerCert
$signerCert | Export-Certificate -FilePath $codeSignerFile
