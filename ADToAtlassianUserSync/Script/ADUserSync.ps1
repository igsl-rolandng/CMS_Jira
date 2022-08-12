Param (
    [switch] $eventTriggered = $false,
    [Parameter(Mandatory=$false)][string] $sAMAccountName,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $computer,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $displayName,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $eventID,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $eventRecordID,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $newUacValue,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $oldUacValue,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $subjectDomainName,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $subjectUserName,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $targetDomainName,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $targetUserName,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $task,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $timeCreated,
    [Parameter(Mandatory=$false)][string][AllowEmptyString()] $userPrincipalName
)

$enc = [system.Text.Encoding]::ASCII

enum EventSource {
    Event
    Manual
    Network
}

enum EventID {
    Success
    Invalid_Parameter
    Cofiguration_Error
    User_Not_Found
    User_Already_Exists
    No_Email
    Atlassian_Error
    Atlassian_Unavailable
    Internal_Error
}

# Event Log Name
$eventLogName = 'User Sync'
$eventSource = [EventSource]::Event
if (-not $eventTriggered) {
    $eventSource = [EventSource]::Manual
}

# Ensure event log source exists
if (-not [System.Diagnostics.EventLog]::SourceExists($eventLogName)) {
    New-EventLog -LogName $eventLogName -source ([Enum]::GetValues([EventSource]))
} 

# Write event log
function LogEventNoConsole(
    [EventID] $eventID, 
    [System.Diagnostics.EventLogEntryType] $entryType, 
    [string] [AllowEmptyString()] $message,
    [EventSource] $source = $eventSource,
    [byte[]] [AllowNull()] $rawData = $null) {
    if ($rawData) {
        LogEvent $eventID $entryType $message $source $false $rawData
    } else {
        LogEvent $eventID $entryType $message $source $false
    }
}

function LogEvent(
    [EventID] $eventID, 
    [System.Diagnostics.EventLogEntryType] $entryType, 
    [string] [AllowEmptyString()] $message,
    [EventSource] $source = $eventSource,
    [boolean] $console = $true,
    [byte[]] [AllowNull()] $rawData = $null) {
    if ($console) {
        Write-Host $message
    }
    if ($rawData) {
        Write-EventLog -LogName $eventLogName -Source $source -EventID $eventID -EntryType $entryType -Message $message -RawData $rawData
    } else {
        Write-EventLog -LogName $eventLogName -Source $source -EventID $eventID -EntryType $entryType -Message $message
    }
}

# Settings file
$settings = "$PSScriptRoot\Settings.ini"

# Email retry
$line = Get-Content -Path $settings | Where-Object { $_ -match '^EmailRetry\s*=\s*[0-9]+\s*$' }
$line -match '^EmailRetry\s*=\s*([0-9]+)\s*$' | Out-Null
$emailRetry = [int] $matches[1]

# Email sleep
$line = Get-Content -Path $settings | Where-Object { $_ -match '^EmailSleep\s*=\s*[0-9]+\s*$' }
$line -match '^EmailSleep\s*=\s*([0-9]+)\s*$' | Out-Null
$emailSleep = [int] $matches[1]

# Get SCIM Base URL from INI
$line = Get-Content -Path $settings | Where-Object { $_ -match '^SCIMBaseURL\s*=' }
$line -match '^SCIMBaseURL\s*=\s*(.+)\s*$' | Out-Null
$scimBaseUrl = $matches[1]
#Write-Host SCIM Base URL: $scimBaseUrl

# Get API Key from INI
$line = Get-Content -Path $settings | Where-Object { $_ -match '^APIKey\s*=' }
$line -match '^APIKey\s*=\s*(.+)\s*$' | Out-Null
$apiKey = $matches[1]
#Write-Host API Key: $apiKey

function GetHeaders() {
    $headers = @{
        'Authorization' = "Bearer $apiKey"
        'Accept' = 'application/json'
        'Content-Type' = 'application/json'
    }
    return $headers
}

# Invoke REST API
function InvokeRest (
        [hashtable] $headers, 
        [object] $body) {
    $code = -1
    $json = $body | ConvertTo-Json
    try {
	    $obj = Invoke-WebRequest -Method 'POST' -Uri $scimBaseUrl -Headers $headers -Body ($body | ConvertTo-Json) | Select-Object
	    # Hide authorization header from log
	    $headers['Authorization'] = '********'
	    $headerString = $headers | Out-String
	    if ($obj) {
	        $rawData = $enc.GetBytes($obj.Content)
	        $status = $obj.StatusCode
            LogEventNoConsole Success Information "Uri: $scimBaseUrl`nHeaders:`n$headerString`nPayload:`n$json`n`nHTTP Status: $status" Network $rawData
	        $code = $obj.StatusCode
	    } else {
	        LogEventNoConsole Cofiguration_Error Error "Uri: $scimBaseUrl`nHeaders:`n$headerString`nPayload:`n$json`n`nHTTP Status: N/A" Network
	    }
	} catch {
	   $msg = $_.Exception.Message
	   LogEventNoConsole Cofiguration_Error Error "Uri: $scimBaseUrl`nHeaders:`n$headerString`nPayload:`n$json`n`nHTTP Status: N/A`nError Message: $msg" Network
    }
    return $code
}

$adUserProperties = @(
    'mail',
    'displayName',
    'surname',
    'givenname',
    'middlename',
    'homePhone',
    'pager',
    'mobile',
    'facsimileTelephoneNumber',
    'ipPhone',
    'personalTitle',
    'department',
    'company'
)

# Get user from AD
function GetUser([string] $sAMAccountName) {
    $user = Get-ADUser -LDAPFilter "(sAMAccountName=$sAMAccountName)" -Properties $adUserProperties
#    Write-Host AD User: ($user | Out-String)
    return $user
}

# Create user
function CreateUser([Microsoft.ActiveDirectory.Management.ADAccount] $user) {
    $headers = GetHeaders
	$body = @{
        "userName" = $user.samaccountname
        "emails" = @(
            @{
                "value" = $user.mail
                "type" = "work"
                "primary" = $true
            }
        )
        "name" = @{
            "formatted" = $user.displayName
            "familyName" = $user.surname
            "givenName" = $user.givenname
            "middleName" = $user.middlename
            "honorificPrefix" = $user.personalTitle
#           "honorificSuffix" = ""
        }
        "displayName" = $user.$displayName
#	    "nickName" = ""
        "title" = $user.title
#	    "preferredLanguage" = ""
        "department" = $user.department
        "organization" = $user.company
#       "timezone" = ""
#       "phoneNumbers" = @(
#           @{
#               "value" = ""
#               "type" = ""
#               "primary" = $true   
#           }
#       )
        "active" = $true
    }
    return InvokeRest $headers $body
}

# Flag to indicate if we return failure to retry scheduled task 
$retry = $false
$retryCount = 0

do {
    $retry = $false
	if ($sAMAccountName) {
	    $user = GetUser($sAMAccountName)
	    if ($user) {
            $userString = ($user | Out-String)
	        if ($user.mail) {
	            $result = CreateUser $user
	            switch ($result) {
	                -1 {
	                    LogEvent Cofiguration_Error Error "Error creating $sAMAccountName. URL $scimBaseURL is not accessible. Please double check SCIM Base URL in $settings"
	                }
	                200 {
	                    LogEvent Success SuccessAudit "User $sAMAccountName has been created on Atlassian Cloud" 
	                }
	                400 {
	                    LogEvent Internal_Error Error "Internal error creating user $sAMAccountName"
	                }
	                401 {
	                    LogEvent Internal_Error Error "Error creating $sAMAccountName. Authorization header is missing"
	                }
	                403 {
	                    LogEvent Cofiguration_Error Error "Error creating $sAMAccountName. Authorization failed. Please double check API Key in $settings"
	                }
	                404 {
	                    LogEvent Cofiguration_Error Error "Error creating $sAMAccountName. Authorization failed. Please double check SCIM Base URL in $settings" 
	                }
	                409 {
	                    LogEvent User_Already_Exists Error "User $sAMAccountName already exists on Atlassian Cloud"
	                }
	                500 {
	                    LogEvent Atlassian_Error Error "Error creating $sAMAccountName. Internal server error from Atlassian Cloud"
	                }
	                503 {
	                    LogEvent Atlassian_Unavailable Error "Error creating $sAMAccountName. Atlassian Cloud service is not available" 
	                }
	                default {
	                    LogEvent Internal_Error Error "Unexpected result $result creating user $sAMAccountName" 
	                }
	            }
	        } else {
	            if ($eventTriggered -and $retryCount -lt $emailRetry) {
	                $retryCount += 1
		            LogEvent No_Email Warning "Retry $retryCount/$emailRetry because user $sAMAccountName does not have mail: $userString"
		            $retry = $true
	            } else {
	                LogEvent No_Email Error "Abort creation because user $sAMAccountName does not have mail: $userString"
	                $retry = $false
	            }
	        }        
	    } else {
	        LogEvent User_Not_Found Error "User $sAMAccountName cannot be found"
	    }
	} else {
	    LogEvent Invalid_Parameter Error 'sAMAccountName is not provided'
	}
	if ($retry) {
	   Start-Sleep -Seconds $emailSleep
	}
} while ($retry)

# TODO Debug using my own REST
#Write-Host Testing InvokeRest API with local site...
#$scimBaseUrl = 'http://laptop.kcwong.igsl:8080/identityiq/plugin/rest/XMLEditor/getClassMethods'
#$base64AuthInfo = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes(("{0}:{1}" -f 'spadmin','admin')))
#$headers = @{
#    'Authorization' = "Basic {0}" -f $base64AuthInfo
#    'Accept' = 'application/json'
#    'Content-Type' = 'application/json'
#}
#$body = "Identity"
#$result = InvokeRest $headers $body
#Write-Host Headers: ($headers | Out-String)
#Write-Host Payload: ($body | ConvertTo-Json)
#Write-Host HTTP Status: $result