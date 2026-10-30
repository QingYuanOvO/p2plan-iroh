@echo off
REM ============================================================
REM  p2plan-iroh build helper  (Minecraft 26.3 / Fabric / iroh)
REM
REM  No personal paths are baked in. A compatible JDK is auto-detected,
REM  everything is overridable by environment variables:
REM
REM    P2PLAN_JDK         (optional) absolute JDK home to force-use,
REM                       no version check. Highest priority.
REM    JAVA_HOME          honoured only if that JDK is >= 21, so a
REM                       stale JAVA_HOME (8/11/17) is skipped --
REM                       Loom refuses to run on < 21.
REM    GRADLE_USER_HOME   Gradle cache. Default: %USERPROFILE%\.gradle
REM    GRADLE_BIN         path to gradle.bat, else found on PATH.
REM
REM  JDK auto-detect picks the HIGHEST version >= 21 among:
REM    - JAVA_HOME
REM    - every runtime under %APPDATA%\.minecraft\runtime\*
REM      (a machine that can launch MC 26.x has the right JDK bundled;
REM       there may be several, e.g. a 21 and a 25 -- pick the 25)
REM    - "java" on PATH
REM  build.gradle compiles with release=24, so javac must be >= 24:
REM  hence "highest wins", not "first that qualifies".
REM
REM  Usage:
REM    build.bat                      gradle build (jar + trim iroh natives)
REM    build.bat irohSelfTest         in-process iroh tunnel self-test
REM    build.bat irohTunnelTest       Tunnels.host/guest path self-test
REM    build.bat irohLongSessionTest  hold a session 100s (regression guard
REM                                   against "connection drops at ~60s")
REM    build.bat irohStability        NodeID persistence -> stable room code
REM    build.bat irohRelayProbe       is the public DERP relay reachable
REM    build.bat irohCrossHost        cross-process probe, host side
REM    build.bat irohCrossGuest       cross-process probe, guest side
REM
REM  NOTES
REM   * --no-watch-fs: native file watching is denied in some
REM     sandboxes/CI; without the flag the build hangs forever at
REM     "Calculating task graph".
REM   * Keep this file ASCII-only: cmd.exe reads .bat in the local
REM     codepage (GBK on zh-CN Windows), non-ASCII comments garble.
REM ============================================================
setlocal EnableExtensions

REM default IPv6 preference (this network's IPv4 egress to overseas
REM mirrors times out; a home-broadband user can drop this)
if not defined JAVA_TOOL_OPTIONS set "JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true"

set "JDK="
set "BEST=0"

REM 1) explicit override, trusted as-is; stop searching
if defined P2PLAN_JDK if exist "%P2PLAN_JDK%\bin\java.exe" (
    set "JDK=%P2PLAN_JDK%"
    goto have_jdk
)

REM 2) existing JAVA_HOME (may be an old 8/11/17 -> skipped if too old)
if defined JAVA_HOME call :tryjdk "%JAVA_HOME%\bin\java.exe"

REM 3) Minecraft launcher runtime(s): several may exist; highest wins
if defined APPDATA for /d %%D in ("%APPDATA%\.minecraft\runtime\*") do call :tryjdk "%%D\bin\java.exe"

REM 4) java on PATH
for /f "delims=" %%I in ('where java 2^>nul') do call :tryjdk "%%I"

if not defined JDK (
    echo [ERROR] No JDK 21+ found. MC 26.x mod build needs JDK 21+ ^(the game needs 25^).
    echo         Fix one of:
    echo           - set P2PLAN_JDK=C:\path\to\jdk-25
    echo           - set JAVA_HOME to a JDK 21+
    echo           - install the Minecraft launcher so a runtime exists under
    echo             %%APPDATA%%\.minecraft\runtime\
    exit /b 1
)

:have_jdk
set "JAVA_HOME=%JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ---- Gradle cache ----
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"

REM ---- locate gradle ----
set "GRADLE_CMD="
if defined GRADLE_BIN if exist "%GRADLE_BIN%" set "GRADLE_CMD="%GRADLE_BIN%""
if not defined GRADLE_CMD if exist "%~dp0.tools\gradle-9.6.0\bin\gradle.bat" set "GRADLE_CMD="%~dp0.tools\gradle-9.6.0\bin\gradle.bat""

cd /d "%~dp0"

if defined GRADLE_CMD goto run_gradle_file
where gradle >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Gradle not found. Set GRADLE_BIN to gradle.bat, put Gradle 9.6+
    echo         on PATH, or drop it at %~dp0.tools\gradle-9.6.0\
    exit /b 1
)
if "%~1"=="" (
    gradle build --no-watch-fs --console=plain
) else (
    gradle %* --no-watch-fs --console=plain
)
goto finish

:run_gradle_file
if "%~1"=="" goto default_build
call %GRADLE_CMD% %* --no-watch-fs --console=plain
goto finish

:default_build
call %GRADLE_CMD% build --no-watch-fs --console=plain
goto finish

REM ============================================================
REM  :tryjdk "path\to\java.exe"
REM  Sets JDK to that java's home if major >= 21 and higher than
REM  the version chosen so far (BEST).
REM
REM  Parsing gotcha: with JAVA_TOOL_OPTIONS set, "java -version"
REM  ALSO prints "Picked up JAVA_TOOL_OPTIONS: ..." to stderr first,
REM  so we grep the line containing the word "version" instead of
REM  blindly taking the 3rd token of the first line (that grabbed
REM  "JAVA_TOOL_OPTIONS:" and made every candidate look "too old" or
REM  "unparseable" -- the real bug this function is written around).
REM ============================================================
:tryjdk
if not exist "%~1" exit /b 0
REM Convert to an 8.3 short path so the "..." java.exe" quoting never
REM breaks inside for /f (Program Files\... contains spaces).
for %%A in ("%~1") do set "JP=%%~fsA"
set "VER="
for /f "tokens=3 delims= " %%a in ('%JP% -version 2^>^&1 ^| findstr /i "version"') do if not defined VER set "VER=%%a"
if not defined VER exit /b 0
set "VER=%VER:"=%"
for /f "tokens=1,2 delims=." %%x in ("%VER%") do (
    if "%%~x"=="1" (set "MAJ=%%~y") else (set "MAJ=%%~x")
)
if not defined MAJ exit /b 0
set /a "CHECK=MAJ" || exit /b 0
if %MAJ% GEQ 21 if %MAJ% GTR %BEST% (
    for %%A in ("%~1\..\..") do set "JDK=%%~fA"
    set "BEST=%MAJ%"
)
exit /b 0

:finish
set "EXITCODE=%ERRORLEVEL%"
endlocal & exit /b %EXITCODE%
