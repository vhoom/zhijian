#Requires -Version 5.1
<#
.SYNOPSIS
构建、对齐、签名并验证 Release APK，保存对应的混淆映射。
.PARAMETER KeyStore
已有发布密钥文件路径；相对路径按调用时的工作目录解析。
.PARAMETER KeyAlias
发布密钥在密钥库中的别名；密码由 apksigner 在本机提示输入。
.PARAMETER JavaHome
可选的 JDK 安装目录；省略时沿用 JAVA_HOME 或 PATH 中的 Java。
.PARAMETER SdkPath
可选的 Android SDK 目录；省略时读取 local.properties、SDK 环境变量或默认安装目录。
.PARAMETER BuildToolsVersion
Android SDK Build Tools 版本；默认使用 36.0.0。
.PARAMETER UniversalOnly
只签名通用 APK；Gradle 构建仍生成全部架构的 APK。
.EXAMPLE
.\scripts\build-signed-release.ps1 -KeyStore 'D:\keys\release.jks' -KeyAlias 'release'
#>
[CmdletBinding()]
param (
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $KeyStore,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $KeyAlias,

    [string] $JavaHome,
    [string] $SdkPath,
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $BuildToolsVersion = '36.0.0',
    [switch] $UniversalOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

<#
.SYNOPSIS
执行外部工具，任一步失败时立即终止后续操作。
.PARAMETER Executable
外部工具的完整路径。
.PARAMETER Arguments
逐项传递给工具的参数；不包含明文密码。
.PARAMETER Step
失败消息中展示的操作名称。
#>
function Invoke-CheckedCommand {
    param (
        [string] $Executable,
        [string[]] $Arguments,
        [string] $Step
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Step 失败，退出码：$LASTEXITCODE。"
    }
}

<#
.SYNOPSIS
查找项目使用的 Android SDK，显式路径优先于本机配置。
.PARAMETER ExplicitPath
调用者指定的 SDK 路径；空值表示自动查找。
.PARAMETER ProjectRoot
仓库根目录，用于定位 local.properties。
#>
function Find-AndroidSdk {
    param (
        [string] $ExplicitPath,
        [string] $ProjectRoot
    )

    if ($ExplicitPath) {
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    $localProperties = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $localProperties -Encoding UTF8) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
                $localSdkPath = $Matches[1].Trim().Replace('\:', ':').Replace('\\', '\')
                return (Resolve-Path -LiteralPath $localSdkPath).Path
            }
        }
    }

    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw '找不到 Android SDK，请通过 -SdkPath 指定其安装目录。'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$originalJavaHome = $env:JAVA_HOME
$locationPushed = $false

try {
    $keyStorePath = (Resolve-Path -LiteralPath $KeyStore).Path
    if (!(Test-Path -LiteralPath $keyStorePath -PathType Leaf)) {
        throw 'KeyStore 必须指向已有的密钥文件。'
    }
    if ($JavaHome) {
        $resolvedJavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
        if (!(Test-Path -LiteralPath (Join-Path $resolvedJavaHome 'bin\java.exe') -PathType Leaf)) {
            throw 'JavaHome 目录中不存在 bin\java.exe。'
        }
        $env:JAVA_HOME = $resolvedJavaHome
    }

    $resolvedSdkPath = Find-AndroidSdk -ExplicitPath $SdkPath -ProjectRoot $projectRoot
    $buildTools = Join-Path $resolvedSdkPath "build-tools\$BuildToolsVersion"
    $zipalign = Join-Path $buildTools 'zipalign.exe'
    $apksigner = Join-Path $buildTools 'apksigner.bat'
    foreach ($tool in @($zipalign, $apksigner)) {
        if (!(Test-Path -LiteralPath $tool -PathType Leaf)) {
            throw "缺少工具：$tool。请安装对应的 SDK Build Tools 或指定 -BuildToolsVersion。"
        }
    }

    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true
    Write-Host '构建 Release APK...'
    Invoke-CheckedCommand -Executable (Join-Path $projectRoot 'gradlew.bat') `
        -Arguments @(':app:assembleRelease', '--console=plain') -Step 'Release 构建'

    # 按 Gradle 本次产物清单处理，避免误选目录中遗留的旧版本 APK。
    $releaseDirectory = Join-Path $projectRoot 'app\build\outputs\apk\release'
    $metadataPath = Join-Path $releaseDirectory 'output-metadata.json'
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $outputs = @($metadata.elements)
    if ($UniversalOnly) {
        $outputs = @($outputs | Where-Object {
            @($_.filters | Where-Object { $_.filterType -eq 'ABI' }).Count -eq 0
        })
    }
    if ($outputs.Count -eq 0) {
        throw '本次构建清单中没有符合条件的 APK。'
    }
    foreach ($output in $outputs) {
        if ([IO.Path]::GetFileName($output.outputFile) -ne $output.outputFile) {
            throw '构建清单中的 APK 文件名不能包含目录。'
        }
        if (!(Test-Path -LiteralPath (Join-Path $releaseDirectory $output.outputFile) -PathType Leaf)) {
            throw "找不到本次构建的 APK：$($output.outputFile)。"
        }
    }

    # 每次运行使用独立目录；保留已完成的签名包及对齐中间产物，便于定位失败。
    $runName = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
    $destination = Join-Path $projectRoot "release-private\signed\$runName"
    $alignedDirectory = Join-Path $destination 'aligned'
    New-Item -ItemType Directory -Path $alignedDirectory | Out-Null
    Write-Host "输出目录：$destination"

    foreach ($output in $outputs) {
        $sourceApk = Join-Path $releaseDirectory $output.outputFile
        $alignedApk = Join-Path $alignedDirectory $output.outputFile
        $signedApk = Join-Path $destination $output.outputFile

        Write-Host "对齐并签名：$($output.outputFile)"
        Invoke-CheckedCommand -Executable $zipalign `
            -Arguments @('-P', '16', '-f', '4', $sourceApk, $alignedApk) -Step 'APK 对齐'
        Invoke-CheckedCommand -Executable $apksigner `
            -Arguments @('sign', '--ks', $keyStorePath, '--ks-key-alias', $KeyAlias,
                '--out', $signedApk, $alignedApk) -Step 'APK 签名'
        Invoke-CheckedCommand -Executable $apksigner `
            -Arguments @('verify', '--verbose', '--print-certs', $signedApk) -Step 'APK 签名验证'
        Invoke-CheckedCommand -Executable $zipalign `
            -Arguments @('-c', '-P', '16', '4', $signedApk) -Step '已签名 APK 对齐验证'
    }

    $mappingPath = Join-Path $projectRoot 'app\build\outputs\mapping\release\mapping.txt'
    Copy-Item -LiteralPath $mappingPath -Destination (Join-Path $destination 'mapping.txt')
    # 只记录实际签名的输出，通用包模式下不包含未签名的架构包。
    $metadata.elements = $outputs
    $metadata | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath (Join-Path $destination 'output-metadata.json') -Encoding UTF8
    Write-Host "完成：已签名并验证 $($outputs.Count) 个 APK，保存在 $destination"
}
catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
    $env:JAVA_HOME = $originalJavaHome
}
