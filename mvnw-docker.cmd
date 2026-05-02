@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "M2_DIR=%USERPROFILE%\.m2"
if not exist "%M2_DIR%" mkdir "%M2_DIR%"

docker run --rm -t ^
  -v "%SCRIPT_DIR%:/workspace" ^
  -v "%M2_DIR%:/root/.m2" ^
  -w /workspace ^
  maven:3.9.9-eclipse-temurin-21 mvn %*
