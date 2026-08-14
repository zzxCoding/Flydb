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

set "JAVA_VERSION="
for /f "tokens=1,2,3" %%A in ('"%JAVA_CMD%" -version 2^>^&1') do (
  if /I "%%B"=="version" if not defined JAVA_VERSION set "JAVA_VERSION=%%~C"
)
if not defined JAVA_VERSION (
  echo Error: Could not determine the Java version. 1>&2
  exit /b 4
)
set "JAVA_MAJOR="
for /f "tokens=1,2 delims=." %%A in ("%JAVA_VERSION%") do (
  if "%%A"=="1" (set "JAVA_MAJOR=%%B") else (set "JAVA_MAJOR=%%A")
)
if not defined JAVA_MAJOR (
  echo Error: Could not determine the Java version: %JAVA_VERSION%. 1>&2
  exit /b 4
)
for /f "delims=0123456789" %%A in ("%JAVA_MAJOR%") do (
  echo Error: Could not determine the Java version: %JAVA_VERSION%. 1>&2
  exit /b 4
)
if %JAVA_MAJOR% LSS 8 (
  echo Error: Flydb requires Java 8 or newer. Current version: %JAVA_VERSION%. 1>&2
  exit /b 4
)

"%JAVA_CMD%" %FLYDB_JAVA_OPTS% -cp "%FLYDB_INSTALL_DIR%\lib\*" com.flydb.cli.FlydbCli %*
exit /b %ERRORLEVEL%
