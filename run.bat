@echo off
cd /d "%~dp0"
echo Building and starting Suwayomi Server with local WebUI...
set "WEBUI_DIR=C:\Users\kevin\OneDrive\Desktop\Apps\Misc\Suwayomi-WebUI-master\Suwayomi-WebUI-master\build"
set "JVM_ARGS=-Dsuwayomi.tachidesk.config.server.webUIExternalPath=%WEBUI_DIR% -Dsuwayomi.tachidesk.config.server.webUIFlavor=Custom"
call gradlew.bat -PappJvmArgs="%JVM_ARGS%" :server:run
pause
