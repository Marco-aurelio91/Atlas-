param([switch]$RerunTests)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repository
try {
    if (Test-Path -LiteralPath 'build/athena-validation.json') {
        Remove-Item -LiteralPath 'build/athena-validation.json'
    }
    $gradleArguments = @('validateAthena', '--console=plain')
    if ($RerunTests) { $gradleArguments += '--rerun-tasks' }
    & .\gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'ATHENA validation failed.' }
    $reports = @(
        'athena/core/build/test-results/test', 'athena/runtime/build/test-results/test',
        'athena/connector-weather/build/test-results/test', 'athena/connector-status/build/test-results/test',
        'core/athena-integration/build/test-results/test', 'core/data/build/test-results/testDebugUnitTest'
    )
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($directory in $reports) {
        $files = @(Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml')
        if ($files.Count -eq 0) { throw "No test results: $directory" }
        foreach ($file in $files) {
            [xml]$report = Get-Content -Raw -LiteralPath $file.FullName
            $tests += [int]$report.testsuite.tests
            $failures += [int]$report.testsuite.failures
            $errors += [int]$report.testsuite.errors
            $skipped += [int]$report.testsuite.skipped
        }
    }
    if ($failures -gt 0 -or $errors -gt 0) { throw 'Test reports contain failures.' }
    $apk = Join-Path $repository 'app/build/outputs/apk/debug/app-debug.apk'
    $summary = [ordered]@{
        validatedAtUtc = [DateTime]::UtcNow.ToString('o')
        tests = $tests; failures = $failures; errors = $errors; skipped = $skipped
        apk = 'app/build/outputs/apk/debug/app-debug.apk'
        apkSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
        gitHead = (& git rev-parse HEAD)
        workingTreeModified = [bool](& git status --porcelain)
        deviceAcceptance = 'See docs/ATHENA_VALIDACAO.md; not implied by unit tests.'
    }
    $summary | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath 'build/athena-validation.json'
    $summary | ConvertTo-Json
} finally { Pop-Location }
