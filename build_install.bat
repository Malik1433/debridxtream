@echo off
setlocal

REM Helper script to build and install the debug APK without opening a terminal manually.

cd /d "%~dp0"
if not exist "gradlew.bat" (
    echo gradlew.bat not found. Run this script from the project root.
    exit /b 1
)

call gradlew.bat assembleDebug || goto :error
call gradlew.bat installDebug || goto :error

echo.
echo Build and install completed successfully.
pause
exit /b 0

:error
echo.
echo Build or install failed. See the output above for details.
pause
exit /b 1

