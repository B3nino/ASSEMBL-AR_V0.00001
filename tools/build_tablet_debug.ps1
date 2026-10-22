# Build the tablet APK without changing Android Studio or project JVM settings.
$ErrorActionPreference = 'Stop'
$taskGradleRoot = Split-Path -Parent $PSScriptRoot
$taskPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
$taskPreviousJavaHome = $env:JAVA_HOME
$taskSocketDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('assemblar-no-unix-' + [guid]::NewGuid().ToString('N'))

# On this Windows host, the JDK binds its selector wakeup AF_UNIX socket but cannot connect
# to it. A deliberately unavailable AF_UNIX directory makes the JDK use its built-in TCP
# fallback. Do not create this directory. The option applies only to this build process.
if (Test-Path -LiteralPath $taskSocketDirectory) { throw 'Unexpected socket fallback path' }
$taskSocketOption = "-Djdk.net.unixdomain.tmpdir=`"$taskSocketDirectory`""

try {
    if (-not $env:JAVA_HOME) {
        $taskStudioJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
        if (Test-Path -LiteralPath (Join-Path $taskStudioJdk 'bin/java.exe')) {
            $env:JAVA_HOME = $taskStudioJdk
        }
    }
    $env:JAVA_TOOL_OPTIONS = "$taskPreviousJavaOptions $taskSocketOption".Trim()
    Push-Location -LiteralPath $taskGradleRoot
    try {
        $taskBuildArguments = @(':app:assembleDebug', ':app:assembleDebugAndroidTest',
            '--offline', '--no-daemon', '--max-workers=2', '-Pandroid.injected.build.abi=arm64-v8a',
            # Keep the debug update usable with Android's normal on-device installer too.
            '-Pandroid.injected.testOnly=false')
        & (Join-Path $taskGradleRoot 'gradlew.bat') @taskBuildArguments
        if ($LASTEXITCODE -ne 0) { throw "Tablet build failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_TOOL_OPTIONS = $taskPreviousJavaOptions
    $env:JAVA_HOME = $taskPreviousJavaHome
}
