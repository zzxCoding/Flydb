@echo off
setlocal
set "FLYDB_INSTALL_DIR=%~dp0.."

if defined JAVA_HOME (
  set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_CMD=java"
)

"%JAVA_CMD%" -version >nul 2>&1
if errorlevel 1 (
  echo Error: Java was not found. Install Java 8 or newer, or set JAVA_HOME. 1>&2
  exit /b 4
)

for /f tokens^=2^ delims^=^" %%V in ('"%JAVA_CMD%" -version 2^>^&1') do set "JAVA_VERSION=%%V" & goto version_read
:version_read
for /f "tokens=1,2 delims=." %%A in ("%JAVA_VERSION%") do (
  if "%%A"=="1" (set "JAVA_MAJOR=%%B") else (set "JAVA_MAJOR=%%A")
)
if %JAVA_MAJOR% LSS 8 (
  echo Error: Flydb requires Java 8 or newer. Current version: %JAVA_VERSION%. 1>&2
  exit /b 4
)

"%JAVA_CMD%" %FLYDB_JAVA_OPTS% -cp "%FLYDB_INSTALL_DIR%\lib\*" com.flydb.cli.FlydbCli %*
exit /b %ERRORLEVEL%
