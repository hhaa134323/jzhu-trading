@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "ROOT_UNIX=%ROOT_DIR:\=/%"

if "%~1"=="" (
  echo Usage: scripts\manage.cmd ^<start^|stop^|restart^|status^|logs^>
  exit /b 1
)

set "GIT_BASH="
if exist "D:\Git\bin\bash.exe" set "GIT_BASH=D:\Git\bin\bash.exe"
if not defined GIT_BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "GIT_BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined GIT_BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "GIT_BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"

if not defined GIT_BASH (
  echo [ERROR] Git Bash not found. Please install Git for Windows.
  exit /b 1
)

"%GIT_BASH%" -lc "cd '%ROOT_UNIX%'; ./scripts/manage.sh %*"
