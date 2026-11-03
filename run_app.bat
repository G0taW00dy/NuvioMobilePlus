@echo off
setlocal

set "ROOT_DIR=%~dp0"
if exist "%ROOT_DIR%venv\Scripts\activate.bat" (
    call "%ROOT_DIR%venv\Scripts\activate.bat"
) else if exist "%ROOT_DIR%..\venv\Scripts\activate.bat" (
    call "%ROOT_DIR%..\venv\Scripts\activate.bat"
) else (
    echo [INFO] Creating virtual environment...
    python -m venv "%ROOT_DIR%venv"
    call "%ROOT_DIR%venv\Scripts\activate.bat"
)

if exist "%ROOT_DIR%requirements.txt" (
    pip install -q -r "%ROOT_DIR%requirements.txt"
) else if exist "%ROOT_DIR%..\requirements.txt" (
    pip install -q -r "%ROOT_DIR%..\requirements.txt"
)

set "PROJECT_DIR=%ROOT_DIR%NuvioMobile"
if not exist "%PROJECT_DIR%" (
    set "PROJECT_DIR=%ROOT_DIR%"
)

echo ==============================================
echo Building Nuvio Mobile Plus Full Release APK...
echo Working directory: %PROJECT_DIR%
echo ==============================================

pushd "%PROJECT_DIR%"
call gradlew.bat :androidApp:assembleFullRelease
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed!
    popd
    exit /b %ERRORLEVEL%
)

echo [INFO] Copying output APKs to %USERPROFILE%\Downloads...
if exist "androidApp\build\outputs\apk\full\release\androidApp-full-universal-release.apk" (
    copy /Y "androidApp\build\outputs\apk\full\release\androidApp-full-universal-release.apk" "%USERPROFILE%\Downloads\NuvioMobilePlus-v0.6.8.apk" >nul
)
if exist "androidApp\build\outputs\apk\full\release\androidApp-full-arm64-v8a-release.apk" (
    copy /Y "androidApp\build\outputs\apk\full\release\androidApp-full-arm64-v8a-release.apk" "%USERPROFILE%\Downloads\NuvioMobilePlus-arm64-v8a-v0.6.8.apk" >nul
)

popd
echo ==============================================
echo [SUCCESS] Build succeeded!
echo Output APKs copied to %USERPROFILE%\Downloads\
echo ==============================================
