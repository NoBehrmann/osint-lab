@echo off
rem Builds and starts OSINT Lab on http://127.0.0.1:8080
rem
rem Prefers a portable JDK 21 + Maven in a sibling ..\.tools folder (so nothing lands on C:); if that
rem folder is absent it falls back to the java and mvn already on your PATH. See the README.
setlocal
set "HERE=%~dp0"
for %%I in ("%HERE%..") do set "BASE=%%~fI"

rem Keep all temp/cache data in a sibling ..\.tmp (off C:).
set "TEMP=%BASE%\.tmp"
set "TMP=%BASE%\.tmp"
if not exist "%TEMP%" mkdir "%TEMP%"
rem Spring Boot leaves an empty docbase dir per start - clear old ones
for /d %%D in ("%TEMP%\tomcat-docbase.*") do rd /s /q "%%D" 2>nul

rem Use the portable toolchain if present, otherwise rely on PATH.
set "JAVA_HOME="
set "MAVEN_HOME="
for /d %%D in ("%BASE%\.tools\jdk-21*") do set "JAVA_HOME=%%~fD"
for /d %%D in ("%BASE%\.tools\apache-maven-*") do set "MAVEN_HOME=%%~fD"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
if defined MAVEN_HOME set "PATH=%MAVEN_HOME%\bin;%PATH%"

where java >nul 2>nul || (echo [ERROR] No java found - install JDK 21 or put a portable one in %BASE%\.tools & exit /b 1)
where mvn >nul 2>nul || (echo [ERROR] No mvn found - install Maven or put a portable one in %BASE%\.tools & exit /b 1)

cd /d "%HERE%"
call mvn -q -DskipTests package || exit /b 1
java -XX:-UsePerfData -Djava.io.tmpdir="%TEMP%" -jar target\osint-lab.jar
