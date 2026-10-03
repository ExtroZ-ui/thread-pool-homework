param(
    [ValidateSet('Build', 'Run', 'Test')][string]$Action = 'Run',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$AppArgs = @()
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskJdk = $null
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $taskJdk = $env:JAVA_HOME
}
if (-not $taskJdk) {
    $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($compiler) { $taskJdk = Split-Path (Split-Path $compiler.Source) }
}
if (-not $taskJdk) {
    $jdkFolder = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $jdkFolder) {
        $taskJdk = Get-ChildItem -LiteralPath $jdkFolder -Directory |
            Sort-Object LastWriteTime -Descending |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not $taskJdk) { throw 'JDK 21+ required. Set JAVA_HOME or install a JDK through IntelliJ IDEA.' }
$javac = Join-Path $taskJdk 'bin\javac.exe'
$java = Join-Path $taskJdk 'bin\java.exe'
New-Item -ItemType Directory -Force -Path 'build\classes', 'build\test-classes' | Out-Null
$sources = @(Get-ChildItem -LiteralPath 'src\main\java' -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
& $javac --release 21 -encoding UTF-8 -d build/classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
if ($Action -eq 'Test') {
    $tests = @(Get-ChildItem -LiteralPath 'src\test\java' -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
    & $javac --release 21 --add-modules jdk.httpserver -encoding UTF-8 -cp build/classes -d build/test-classes @tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & $java --add-modules jdk.httpserver -cp 'build/classes;build/test-classes' ru.homework.threadpool.IntegrationTest
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
} elseif ($Action -eq 'Run') {
    & $java -cp build/classes ru.homework.threadpool.Main @AppArgs
    if ($LASTEXITCODE -ne 0) { throw 'Application failed' }
} else { Write-Output 'Build completed' }
