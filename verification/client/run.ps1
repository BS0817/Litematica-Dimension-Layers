param(
    [Parameter(Mandatory)][string]$LauncherMetadata,
    [Parameter(Mandatory)][string]$FixtureWorld,
    [Parameter(Mandatory)][string]$Mods,
    [Parameter(Mandatory)][string]$ApiModules,
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidateSet('en_us','ko_kr')][string]$Language = 'en_us',
    [string]$RunId = '1'
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$meta = (Resolve-Path $LauncherMetadata).Path
$fixture = (Resolve-Path $FixtureWorld).Path
$version = '26.3-0.19.5'
$manifest = Get-Content -Raw "$meta/versions/$version/$version.json" | ConvertFrom-Json
$classPath = @(foreach ($lib in $manifest.libraries) {
    if ($lib.rules -and !($lib.rules | Where-Object { $_.action -eq 'allow' -and $_.os.name -eq 'windows' -and (!$_.os.arch -or $_.os.arch -eq 'x86_64') })) { continue }
    if ($lib.name -match 'natives-windows-(arm64|x86)$') { continue }
    $artifact = $lib.downloads.artifact.path
    if (!$artifact) {
        $group, $module, $id = $lib.name.Split(':')
        $artifact = "$($group.Replace('.','/'))/$module/$id/$module-$id.jar"
    }
    $resolved = Join-Path "$meta/libraries" $artifact
    if (!(Test-Path -LiteralPath $resolved)) { throw "Missing dependency $artifact" }
    $resolved
})
$classPath += "$meta/versions/$version/$version.jar"
$run = Join-Path $project ".verification/client-$RunId-$Language"
if (Test-Path -LiteralPath $run) { throw "Run folder already exists: $run" }
New-Item -ItemType Directory -Path "$run/mods","$run/driver","$run/native","$run/saves" -Force | Out-Null
Copy-Item -LiteralPath $fixture -Destination "$run/saves/Fixture" -Recurse
Copy-Item -LiteralPath $fixture -Destination "$run/saves/Fixture-B" -Recurse
foreach ($glob in 'litematica-*.jar','malilib-*.jar','fabric-api-*.jar') {
    Get-ChildItem $Mods -Filter $glob | Copy-Item -Destination "$run/mods"
}
$production = "$project/build/libs/litematica-dimension-layers-0.1.0+mc26.3.jar"
Copy-Item -LiteralPath $production -Destination "$run/mods"
$compile = (@($classPath) + @(Get-ChildItem $ApiModules -Filter '*.jar' | ForEach-Object FullName) +
    @(Get-ChildItem "$run/mods" -Filter '*.jar' | ForEach-Object FullName)) -join ';'
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -proc:none -cp $compile -d "$run/driver" "$PSScriptRoot/DimensionLayersSmoke.java"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
'{"schemaVersion":1,"id":"dimension_layers_smoke","version":"1.0","environment":"client","depends":{"litematica_dimension_layers":"*"},"entrypoints":{"client":["dev.dimensionlayers.DimensionLayersSmoke"]}}' |
    Set-Content -LiteralPath "$run/fabric.mod.json" -Encoding utf8
& "$JavaHome/bin/jar.exe" cf "$run/mods/dimension-layers-smoke.jar" -C "$run/driver" . -C $run fabric.mod.json
if ($LASTEXITCODE) { exit $LASTEXITCODE }
@('guiScale:2',"lang:$Language",'pauseOnLostFocus:false','tutorialStep:none','renderDistance:5','simulationDistance:5','enableVsync:false','maxFps:60') |
    Set-Content "$run/options.txt"
$argumentFile = Join-Path $run 'java-args.txt'
@(
    '-Xmx2G'
    '--enable-native-access=ALL-UNNAMED'
    '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED'
    ('"-XX:ErrorFile=' + "$run/hs_err_pid%p.log".Replace('\','/') + '"')
    ('"-Dorg.lwjgl.system.SharedLibraryExtractPath=' + "$run/native".Replace('\','/') + '"')
    '-cp'
    ('"' + ($classPath -join ';').Replace('\','/') + '"')
    $manifest.mainClass
    '--username Player0'
    '--version 26.3'
    '--versionType release'
    ('--gameDir "' + $run.Replace('\','/') + '"')
    ('--assetsDir "' + "$meta/assets".Replace('\','/') + '"')
    ('--assetIndex ' + $manifest.assetIndex.id)
    '--accessToken 0'
    '--quickPlaySingleplayer Fixture'
    '--width 1024'
    '--height 680'
) | Set-Content -LiteralPath $argumentFile -Encoding utf8
Push-Location $run
try {
    $runtime = Get-ChildItem "$meta/java_versions" -Recurse -Filter java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    $launcher = if ($runtime) { $runtime.FullName } else { "$JavaHome/bin/java.exe" }
    & $launcher ('@' + $argumentFile) *> "$run/launch.log"
    $code = $LASTEXITCODE
    "Java exit code $code" | Set-Content "exit.txt"
    if (Test-Path 'dimension-layers-smoke.txt') { Get-Content 'dimension-layers-smoke.txt' }
    if ($code -or !(Test-Path 'dimension-layers-smoke.txt') -or !(Select-String -Path 'dimension-layers-smoke.txt' -Pattern '^PASS ')) {
        Get-Content 'launch.log' -Tail 85
        exit 1
    }
} finally { Pop-Location }
