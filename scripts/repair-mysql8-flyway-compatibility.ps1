[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$MysqlHost = '127.0.0.1',
    [int]$MysqlPort = 3307,
    [string]$Database = 'hmdp',
    [string]$Username = 'root',
    [string]$Password = '',
    [string]$MysqlPath = '',
    [string]$MavenCommand = 'mvn',
    [string]$HistoryBackupDirectory = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compatLocation = Join-Path $root 'src/main/resources/db/mysql8-compat'
$historyBridge = Join-Path $root 'docs/review/sql/mysql8-flyway-history-bridge.sql'
$successfulHistoryReconcile = Join-Path $root 'docs/review/sql/mysql8-flyway-success-checksum-reconcile.sql'
$migrationLocation = (Join-Path $root 'src/main/resources/db/migration').Replace('\', '/')
$compatFlywayLocation = $compatLocation.Replace('\', '/')
$pomPath = Join-Path $root 'pom.xml'
$targetDirectory = [IO.Path]::GetFullPath((Join-Path $root 'target'))
if ([string]::IsNullOrWhiteSpace($HistoryBackupDirectory)) {
    $backupDirectory = Join-Path $root '.local-backups/flyway-compat'
} elseif ([IO.Path]::IsPathRooted($HistoryBackupDirectory)) {
    $backupDirectory = $HistoryBackupDirectory
} else {
    $backupDirectory = Join-Path $root $HistoryBackupDirectory
}
$backupDirectory = [IO.Path]::GetFullPath($backupDirectory)
$targetPrefix = $targetDirectory.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if ($backupDirectory.Equals($targetDirectory, [StringComparison]::OrdinalIgnoreCase) -or
    $backupDirectory.StartsWith($targetPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'History backup directory must not be inside the Maven target directory'
}

if (-not (Test-Path -LiteralPath $compatLocation -PathType Container)) {
    throw "Compatibility migration directory was not found: $compatLocation"
}
if (-not (Test-Path -LiteralPath $historyBridge -PathType Leaf)) {
    throw "History bridge SQL was not found: $historyBridge"
}
if (-not (Test-Path -LiteralPath $successfulHistoryReconcile -PathType Leaf)) {
    throw "Successful history reconciliation SQL was not found: $successfulHistoryReconcile"
}

if ([string]::IsNullOrWhiteSpace($MysqlPath)) {
    $mysqlCommand = Get-Command mysql -ErrorAction SilentlyContinue
    if ($null -eq $mysqlCommand) {
        throw 'mysql.exe was not found; pass -MysqlPath explicitly'
    }
    $MysqlPath = $mysqlCommand.Source
}
if (-not (Test-Path -LiteralPath $MysqlPath -PathType Leaf)) {
    throw "mysql.exe was not found: $MysqlPath"
}
if (-not (Get-Command $MavenCommand -ErrorAction SilentlyContinue)) {
    throw "Maven command was not found: $MavenCommand"
}

$jdbcUrl = "jdbc:mysql://$MysqlHost`:$MysqlPort/${Database}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$mysqlArguments = @('--protocol=tcp', "--host=$MysqlHost", "--port=$MysqlPort", "--user=$Username", "--database=$Database", '--default-character-set=utf8mb4')
$oldMysqlPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')

function Invoke-MysqlQuery([string]$Query) {
    $result = & $script:MysqlPath @script:mysqlArguments -NBe $Query
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed with exit code $LASTEXITCODE"
    }
    return ($result -join "`n")
}

function Invoke-MysqlFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "SQL file was not found: $Path"
    }
    Get-Content -Raw -Encoding UTF8 -LiteralPath $Path |
        & $script:MysqlPath @script:mysqlArguments
    if ($LASTEXITCODE -ne 0) {
        throw "mysql failed while executing $Path with exit code $LASTEXITCODE"
    }
}

function Invoke-Flyway([string]$Goal, [string[]]$ExtraArguments) {
    $oldFlywayUrl = [Environment]::GetEnvironmentVariable('FLYWAY_URL', 'Process')
    $oldFlywayUser = [Environment]::GetEnvironmentVariable('FLYWAY_USER', 'Process')
    $oldFlywayPassword = [Environment]::GetEnvironmentVariable('FLYWAY_PASSWORD', 'Process')
    try {
        # Passing a JDBC URL containing '&' as a Maven .cmd argument lets cmd.exe
        # treat it as a command separator. Flyway also supports these process
        # environment properties, which preserve the URL and keep credentials out
        # of the command line.
        $env:FLYWAY_URL = $script:jdbcUrl
        $env:FLYWAY_USER = $script:Username
        $env:FLYWAY_PASSWORD = $script:Password
        $arguments = @("org.flywaydb:flyway-maven-plugin:8.5.13:$Goal") + $ExtraArguments
        & $script:MavenCommand '-f' $script:pomPath @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Flyway $Goal failed with exit code $LASTEXITCODE"
        }
    } finally {
        if ($null -eq $oldFlywayUrl) {
            Remove-Item Env:FLYWAY_URL -ErrorAction SilentlyContinue
        } else {
            $env:FLYWAY_URL = $oldFlywayUrl
        }
        if ($null -eq $oldFlywayUser) {
            Remove-Item Env:FLYWAY_USER -ErrorAction SilentlyContinue
        } else {
            $env:FLYWAY_USER = $oldFlywayUser
        }
        if ($null -eq $oldFlywayPassword) {
            Remove-Item Env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
        } else {
            $env:FLYWAY_PASSWORD = $oldFlywayPassword
        }
    }
}

if (-not $PSCmdlet.ShouldProcess("$MysqlHost`:$MysqlPort/$Database", 'repair the MySQL 8 Flyway history bridge')) {
    return
}

try {
    if ([string]::IsNullOrEmpty($Password)) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    } else {
        $env:MYSQL_PWD = $Password
    }

    $serverVersion = Invoke-MysqlQuery 'SELECT VERSION()'
    if ($serverVersion -notmatch '^8\.' -or $serverVersion -match 'MariaDB') {
        throw "This bridge requires Oracle MySQL 8; detected $serverVersion"
    }

    $historyTableExists = (Invoke-MysqlQuery @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'
"@).Trim() -eq '1'

    if ($historyTableExists) {
        New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
        $backupPath = Join-Path $backupDirectory "flyway_schema_history-$stamp.tsv"
        $backup = & $MysqlPath @mysqlArguments --batch --raw -e 'SELECT * FROM flyway_schema_history ORDER BY installed_rank'
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to back up flyway_schema_history'
        }
        $backup | Set-Content -Encoding UTF8 -LiteralPath $backupPath
        Write-Host "Flyway history backup: $backupPath"

        $approvalRows = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE version = '20260720.03'
  AND description <=> 'approval outbox and index recovery'
  AND type <=> 'SQL'
  AND script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql'
  AND checksum <=> 2143241596
"@)
        $shadowRows = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE version = '20260721.01'
  AND description <=> 'knowledge shadow index activation'
  AND type <=> 'SQL'
  AND script <=> 'V20260721_01__knowledge_shadow_index_activation.sql'
  AND checksum <=> 814957484
"@)
        $approvalLegacySuccess = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE version = '20260720.03'
  AND description <=> 'approval outbox and index recovery'
  AND type <=> 'SQL'
  AND script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql'
  AND checksum <=> -128447297
  AND success = 1
"@)
        $shadowLegacySuccess = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE version = '20260721.01'
  AND description <=> 'knowledge shadow index activation'
  AND type <=> 'SQL'
  AND script <=> 'V20260721_01__knowledge_shadow_index_activation.sql'
  AND checksum <=> -946922017
  AND success = 1
"@)
        $targetRows = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE version IN ('20260720.03', '20260721.01')
"@)

        if ($targetRows -eq 2 -and
            $approvalLegacySuccess -eq 1 -and $shadowLegacySuccess -eq 1) {
            Invoke-MysqlFile $successfulHistoryReconcile
            $reconciledSuccesses = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE success = 1
  AND ((version = '20260720.03' AND checksum <=> 2143241596)
    OR (version = '20260721.01' AND checksum <=> 814957484))
"@)
            if ($reconciledSuccesses -ne 2) {
                throw 'Successful checksum reconciliation did not produce both published contracts'
            }
            Invoke-MysqlQuery 'DROP TABLE IF EXISTS flyway_mysql8_compat_history' | Out-Null
            Invoke-Flyway 'migrate' @("-Dflyway.locations=filesystem:$script:migrationLocation")
            Invoke-Flyway 'validate' @("-Dflyway.locations=filesystem:$script:migrationLocation")
            Write-Host 'Known successful checksum drift was reconciled; normal migrations are current.'
            return
        }

        if ($approvalRows -gt 1 -or $shadowRows -gt 1 -or
            $targetRows -ne ($approvalRows + $shadowRows)) {
            throw 'Target Flyway history rows do not match the published migration contracts'
        }

        $approvalSuccess = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE version = '20260720.03' AND success = 1
  AND checksum <=> 2143241596
"@)
        $shadowSuccess = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE version = '20260721.01' AND success = 1
  AND checksum <=> 814957484
"@)
        if ($shadowSuccess -eq 1 -and $approvalSuccess -ne 1) {
            throw 'Flyway history contains 20260721.01 without successful 20260720.03'
        }
        if ($approvalSuccess -eq 1 -and $shadowSuccess -eq 1) {
            Invoke-MysqlQuery 'DROP TABLE IF EXISTS flyway_mysql8_compat_history' | Out-Null
            Invoke-Flyway 'migrate' @("-Dflyway.locations=filesystem:$script:migrationLocation")
            Invoke-Flyway 'validate' @("-Dflyway.locations=filesystem:$script:migrationLocation")
            Write-Host 'MySQL 8 Flyway compatibility bridge was already complete; normal migrations are current.'
            return
        }

        $unexpectedSuccess = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE success = 1 AND version IS NOT NULL AND version > '20260720.02'
  AND version NOT IN ('20260720.03', '20260721.01')
"@)
        $failedOther = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE success = 0 AND version NOT IN ('20260720.03', '20260721.01')
"@)
        if ($unexpectedSuccess -gt 0 -or $failedOther -gt 0) {
            throw 'Unexpected Flyway history exists after the safe base; inspect it before bridging'
        }

        $failedTargets = [int](Invoke-MysqlQuery @"
SELECT COUNT(*) FROM flyway_schema_history
WHERE success = 0 AND version IN ('20260720.03', '20260721.01')
"@)
        if ($failedTargets -gt 0) {
            $deletedFailedTargets = [int](Invoke-MysqlQuery @"
DELETE FROM flyway_schema_history
WHERE success = 0
  AND ((version = '20260720.03'
        AND description <=> 'approval outbox and index recovery'
        AND type <=> 'SQL'
        AND script <=> 'V20260720_03__approval_outbox_and_index_recovery.sql'
        AND checksum <=> 2143241596)
    OR (version = '20260721.01'
        AND description <=> 'knowledge shadow index activation'
        AND type <=> 'SQL'
        AND script <=> 'V20260721_01__knowledge_shadow_index_activation.sql'
        AND checksum <=> 814957484));
SELECT ROW_COUNT();
"@)
            if ($deletedFailedTargets -ne $failedTargets) {
                throw 'Failed target cleanup did not match the validated Flyway history rows'
            }
            Write-Host "Removed $deletedFailedTargets validated failed target migration marker(s)."
        } else {
            Write-Host 'No failed target migration marker exists; skipping targeted history cleanup.'
        }
    } else {
        Write-Host 'flyway_schema_history does not exist; skipping history backup and targeted cleanup.'
    }

    Invoke-Flyway 'migrate' @(
        '-Dflyway.target=20260720.02',
        '-Dflyway.baselineOnMigrate=true',
        "-Dflyway.locations=filesystem:$script:migrationLocation"
    )

    Invoke-MysqlQuery 'DROP TABLE IF EXISTS flyway_mysql8_compat_history' | Out-Null
    Invoke-Flyway 'migrate' @(
        '-Dflyway.table=flyway_mysql8_compat_history',
        '-Dflyway.baselineOnMigrate=true',
        '-Dflyway.baselineVersion=20260720.02',
        "-Dflyway.locations=filesystem:$script:compatFlywayLocation"
    )

    Invoke-MysqlFile $historyBridge
    Invoke-MysqlQuery 'DROP TABLE IF EXISTS flyway_mysql8_compat_history' | Out-Null

    Invoke-Flyway 'migrate' @(
        "-Dflyway.locations=filesystem:$script:migrationLocation"
    )
    Invoke-Flyway 'validate' @(
        "-Dflyway.locations=filesystem:$script:migrationLocation"
    )
    Write-Host 'MySQL 8 Flyway compatibility bridge completed; normal migrations are current.'
}
finally {
    if ($null -eq $oldMysqlPassword) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    } else {
        $env:MYSQL_PWD = $oldMysqlPassword
    }
}
