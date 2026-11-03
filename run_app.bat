@echo off
echo ==============================================
echo Building Nuvio Mobile Plus Full Release APK...
echo ==============================================
call gradlew.bat :androidApp:assembleFullRelease
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed!
    exit /b %ERRORLEVEL%
)
echo [SUCCESS] Build succeeded! Output APKs located in androidApp\build\outputs\apk\full\release\
