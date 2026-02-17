@echo off
setlocal enabledelayedexpansion

:: ========================================================================
:: Code Reviewer Run Script
:: ========================================================================

set "BIN_DIR=bin"
set "SRC_DIR=src"
set "MAIN_CLASS=com.reviewer.Main"
set "JAR_PATH=%~dp0code-reviewer.jar"

:: If jar exists, use it instead of compiling
if exist "%JAR_PATH%" (
    goto :run_jar
)

echo [1/2] Compiling Code Reviewer...
if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

:: Find all java files
set "JAVA_FILES="
for /r "%SRC_DIR%" %%f in (*.java) do (
    set "JAVA_FILES=!JAVA_FILES! "%%f""
)

javac -d "%BIN_DIR%" -sourcepath "%SRC_DIR%" !JAVA_FILES!

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Compilation failed. Please check your Java installation and source files.
    exit /b %ERRORLEVEL%
)

:run_jar
:: Check for --install-hook command
if "%~1"=="--install-hook" (
    echo [INSTALL] Installing pre-commit hook...
    if not exist ".git" (
        echo [ERROR] No .git directory found. Please run this in the root of your git repository.
        exit /b 1
    )
    if not exist ".git\hooks" mkdir ".git\hooks"
    
    set "INSTALL_DIR=C:\dev-tools\code-reviewer"
    set "HOOK_SOURCE=%~dp0pre-commit"
    if not exist "%HOOK_SOURCE%" set "HOOK_SOURCE=src\pre-commit"
    set "HOOK_DEST=.git\hooks\pre-commit"
    
    if exist "%INSTALL_DIR%\pre-commit" (
        echo [INFO] Using global installation from %INSTALL_DIR%
        set "HOOK_SOURCE_FILE=%INSTALL_DIR%\pre-commit"
        set "HOOK_BIN_PATH=%INSTALL_DIR%\review.bat"
    ) else (
        echo [INFO] Global installation not found. Using current location and updating paths...
        set "HOOK_SOURCE_FILE=%~dp0pre-commit"
        if not exist "!HOOK_SOURCE_FILE!" set "HOOK_SOURCE_FILE=%~dp0src\pre-commit"
        set "HOOK_BIN_PATH=%~dp0review.bat"
        if not exist "!HOOK_BIN_PATH!" set "HOOK_BIN_PATH=%~dp0run-reviewer.bat"
    )
    
    set "ESCAPED_ABS_PATH=!HOOK_BIN_PATH:\=\\!"
    powershell -NoProfile -Command "$dest = Join-Path (Get-Location) '.git\hooks\pre-commit'; $src = [System.IO.Path]::GetFullPath('!HOOK_SOURCE_FILE!'); if (Test-Path $src) { $content = [System.IO.File]::ReadAllText($src); $content = $content -replace '\./run-reviewer\.bat', '!ESCAPED_ABS_PATH!'; [System.IO.File]::WriteAllText($dest, $content); Write-Host '[SUCCESS] Hook file updated at' $dest } else { throw 'Source hook file not found at ' + $src }"

    
    echo [SUCCESS] Pre-commit hook installed successfully in %HOOK_DEST%
    exit /b 0
)

echo [2/2] Running Analysis...
echo.

:: Run the application
if exist "%JAR_PATH%" (
    java -jar "%JAR_PATH%" %*
) else (
    java -cp "%BIN_DIR%" %MAIN_CLASS% %*
)

if %ERRORLEVEL% neq 0 (
    echo.
    echo [FINISH] Analysis complete with findings or errors (Exit Code: %ERRORLEVEL%)
) else (
    echo.
    echo [FINISH] Analysis complete. No critical issues found.
)

endlocal
