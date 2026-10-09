$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceDir = Join-Path $root 'src'
$resourceDir = Join-Path $root 'res'
$buildDir = Join-Path $root 'build'
$classesDir = Join-Path $buildDir 'classes'
$verifiedDir = Join-Path $buildDir 'verified'
$jarPath = Join-Path $root 'KinIRC.jar'
$jadPath = Join-Path $root 'app.jad'
$manifestPath = Join-Path $root 'manifest.mf'

$javacPath = $null
$javacCommand = Get-Command javac -ErrorAction SilentlyContinue
if ($javacCommand) {
    $javacPath = $javacCommand.Source
} elseif ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    if (Test-Path -LiteralPath $candidate) {
        $javacPath = $candidate
    }
}
$jarToolPath = $null
$jarCommand = Get-Command jar -ErrorAction SilentlyContinue
if ($jarCommand) {
    $jarToolPath = $jarCommand.Source
} elseif ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (Test-Path -LiteralPath $candidate) {
        $jarToolPath = $candidate
    }
}
if (-not $javacPath) {
    throw 'No se encontro javac. Configura PATH o JAVA_HOME.'
}
if (-not $jarToolPath) {
    throw 'No se encontro jar. Configura PATH o JAVA_HOME.'
}

$cldcApi = $env:CLDC_API
$midpApi = $env:MIDP_API
if ($env:J2ME_HOME) {
    if (-not $cldcApi) {
        foreach ($candidate in @((Join-Path $env:J2ME_HOME 'lib\cldcapi11.jar'), (Join-Path $env:J2ME_HOME 'lib\cldcapi1.1.jar'), (Join-Path $env:J2ME_HOME 'lib\cldcapi.jar'))) {
            if (Test-Path -LiteralPath $candidate) {
                $cldcApi = $candidate
                break
            }
        }
    }
    if (-not $midpApi) {
        foreach ($candidate in @((Join-Path $env:J2ME_HOME 'lib\midpapi20.jar'), (Join-Path $env:J2ME_HOME 'lib\midpapi2.0.jar'), (Join-Path $env:J2ME_HOME 'lib\midpapi.jar'))) {
            if (Test-Path -LiteralPath $candidate) {
                $midpApi = $candidate
                break
            }
        }
    }
}
if (-not $cldcApi -or -not (Test-Path -LiteralPath $cldcApi)) {
    throw 'No se encontro CLDC_API o J2ME_HOME/lib/cldcapi11.jar.'
}
if (-not $midpApi -or -not (Test-Path -LiteralPath $midpApi)) {
    throw 'No se encontro MIDP_API o J2ME_HOME/lib/midpapi20.jar.'
}

$preverifyPath = $null
if ($env:PREVERIFY -and (Test-Path -LiteralPath $env:PREVERIFY)) {
    $preverifyPath = $env:PREVERIFY
} elseif ($env:J2ME_HOME) {
    $candidate = Join-Path $env:J2ME_HOME 'bin\preverify.exe'
    if (Test-Path -LiteralPath $candidate) {
        $preverifyPath = $candidate
    }
} else {
    $preverifyCommand = Get-Command preverify -ErrorAction SilentlyContinue
    if ($preverifyCommand) {
        $preverifyPath = $preverifyCommand.Source
    }
}
if (-not $preverifyPath) {
    throw 'No se encontro preverify. Configura PREVERIFY o J2ME_HOME/bin/preverify.exe.'
}

if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $classesDir,$verifiedDir | Out-Null
$sourceFiles = @(Get-ChildItem -LiteralPath $sourceDir -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$bootClasspath = $cldcApi + ';' + $midpApi
& $javacPath -source 1.3 -target 1.1 -encoding UTF-8 -bootclasspath $bootClasspath -d $classesDir $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw 'javac termino con error.'
}
& $preverifyPath -classpath $bootClasspath -d $verifiedDir $classesDir
if ($LASTEXITCODE -ne 0) {
    throw 'preverify termino con error.'
}
$resourceItems = @(Get-ChildItem -LiteralPath $resourceDir -Recurse -File -ErrorAction SilentlyContinue)
foreach ($item in $resourceItems) {
    $relative = $item.FullName.Substring($resourceDir.Length).TrimStart([char]'\')
    $target = Join-Path $verifiedDir $relative
    $targetParent = Split-Path -Parent $target
    New-Item -ItemType Directory -Force -Path $targetParent | Out-Null
    Copy-Item -LiteralPath $item.FullName -Destination $target -Force
}
& $jarToolPath cfm $jarPath $manifestPath -C $verifiedDir .
if ($LASTEXITCODE -ne 0) {
    throw 'jar termino con error.'
}
$size = (Get-Item -LiteralPath $jarPath).Length
$jad = @(
    'MIDlet-Name: KinIRC'
    'MIDlet-Version: 1.0'
    'MIDlet-Vendor: KinIRC'
    'MIDlet-1: KinIRC,/icon.png,kinirc.IrcMidlet'
    ('MIDlet-Jar-URL: ' + [System.IO.Path]::GetFileName($jarPath))
    ('MIDlet-Jar-Size: ' + $size)
    'MicroEdition-Profile: MIDP-2.0'
    'MicroEdition-Configuration: CLDC-1.1'
    'MIDlet-Permissions: javax.microedition.io.Connector.socket'
) -join "`r`n"
[System.IO.File]::WriteAllText($jadPath, $jad + "`r`n")
Write-Output ('JAR creado: ' + $jarPath)
Write-Output ('JAD creado: ' + $jadPath)
