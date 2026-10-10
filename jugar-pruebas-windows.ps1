# Corre las pruebas de juego de Chorus en Windows (se abre Minecraft varias veces).
#
#   .\jugar-pruebas-windows.ps1              todas; las de Windows (WindowsTest) solo corren aquí
#   .\jugar-pruebas-windows.ps1 -Calidad 1080
#
# Las capturas quedan en %TEMP%\chorus-shots (o en -Salida).
param([string]$Calidad = "720", [string]$Salida = (Join-Path $env:TEMP "chorus-shots"))
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force $Salida | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Dchorus.out=$Salida -Dchorus.quality=$Calidad $env:JAVA_TOOL_OPTIONS"
Write-Host "[chorus] Corriendo las pruebas de juego; capturas en $Salida"
& .\gradlew.bat runClientGameTest -q
exit $LASTEXITCODE
