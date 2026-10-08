param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
$poolProjectRoot = Split-Path -Parent $PSScriptRoot
$poolOriginalJavaHome = $env:JAVA_HOME
try {
    if ([string]::IsNullOrWhiteSpace($env:POOLPILOT_ADMIN_TOKEN)) {
        throw '请先设置 POOLPILOT_ADMIN_TOKEN，再启动管理台。'
    }
    if (-not $JavaHome) {
        $poolJavaDetails = & java -XshowSettings:properties -version 2>&1 | Out-String
        $poolHomeMatch = [regex]::Match($poolJavaDetails, 'java\.home\s*=\s*([^\r\n]+)')
        if (-not $poolHomeMatch.Success) { throw '无法定位 JDK；请用 -JavaHome 指定 JDK 17 或 21。' }
        $JavaHome = $poolHomeMatch.Groups[1].Value.Trim()
    }
    $poolCompiler = Join-Path $JavaHome 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $poolCompiler)) { throw 'JavaHome 必须指向完整 JDK。' }
    $poolCompilerVersion = & $poolCompiler -version 2>&1 | Out-String
    $poolVersionMatch = [regex]::Match($poolCompilerVersion,'javac\s+(\d+)')
    if (-not $poolVersionMatch.Success -or [int]$poolVersionMatch.Groups[1].Value -lt 17) { throw '需要 JDK 17 或更高版本。' }
    $env:JAVA_HOME = $JavaHome
    Push-Location -LiteralPath $poolProjectRoot
    try {
        & '.\mvnw.cmd' -B -ntp '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw '项目构建失败。' }
        & (Join-Path $JavaHome 'bin\java.exe') -jar '.\poolpilot-demo\target\poolpilot-demo-0.1.0.jar'
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $poolOriginalJavaHome }
