@echo off
rem ============================================================
rem  Furgo Energia - compilar e instalar en el movil
rem  Uso:  compilar.bat            (debug + instalar si hay movil)
rem        compilar.bat release    (release firmada con clave debug)
rem        compilar.bat sininstalar
rem ============================================================
setlocal
cd /d "%~dp0"

rem El JAVA_HOME global del PC es Java 8 a proposito: aqui forzamos JDK 17 solo para esta compilacion
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist local.properties (
  echo sdk.dir=%ANDROID_HOME:\=\\%> local.properties
)

set "TAREA=assembleDebug"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
if /i "%1"=="release" (
  set "TAREA=assembleRelease"
  set "APK=app\build\outputs\apk\release\app-release.apk"
)

echo.
echo === Compilando (%TAREA%) ===
rem Ruta completa: en este PC cmd no busca ejecutables en la carpeta actual
call "%~dp0gradlew.bat" %TAREA% -x lint --console=plain
if errorlevel 1 (
  echo.
  echo [X] La compilacion ha fallado.
  exit /b 1
)
echo [OK] APK: %APK%

if /i "%1"=="sininstalar" exit /b 0

rem adb: preferimos el de scrcpy, si no el del SDK
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Genymobile.scrcpy*") do (
  for /d %%E in ("%%D\scrcpy-win64-*") do if exist "%%E\adb.exe" set "ADB=%%E\adb.exe"
)

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo [!] No hay movil conectado: instala el APK a mano o conecta el movil y repite.
  exit /b 0
)
echo.
echo === Instalando en el movil ===
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo [X] No se ha podido instalar.
  exit /b 1
)
"%ADB%" shell am start -n com.furgo.energia/.MainActivity >nul
echo [OK] Instalada y abierta.
endlocal
