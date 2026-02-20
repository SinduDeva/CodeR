@echo off
setlocal enabledelayedexpansion

echo.
echo   +=========================================+
echo   ^|  Code Reviewer - Installation           ^|
echo   ^|  Your Team's Pre-Commit Safety Net      ^|
echo   +=========================================+
echo.

set "INSTALL_DIR=C:\dev-tools\code-reviewer"
set "REPO_DIR=%~dp0"
set "SRC_DIR=%REPO_DIR%src"
set "BIN_DIR=%REPO_DIR%bin"
set "MAIN_CLASS=com.reviewer.Main"

echo   Creating directory: %INSTALL_DIR%
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

echo   [1/2] Compiling Code Reviewer...
if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

:: Find all java files
set "JAVA_FILES="
for /r "%SRC_DIR%" %%f in (*.java) do (
    set "JAVA_FILES=!JAVA_FILES! "%%f""
)

:: Setup classpath for compilation if lib exists
set "CP=."
if exist "%REPO_DIR%lib" (
    for %%i in ("%REPO_DIR%lib\*.jar") do (
        set "CP=!CP!;%%i"
    )
)

javac -d "%BIN_DIR%" -sourcepath "%SRC_DIR%" -classpath "!CP!" !JAVA_FILES!

if %ERRORLEVEL% neq 0 (
    echo.
    echo   [ERROR] Compilation failed. Please check your Java installation.
    pause
    exit /b %ERRORLEVEL%
)

echo   [2/2] Building Jar...
set "JAR_CMD=jar"
where jar >nul 2>nul
if %ERRORLEVEL% neq 0 (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\jar.exe" (
            set "JAR_CMD="%JAVA_HOME%\bin\jar.exe""
        )
    )
)

%JAR_CMD% cfe "%INSTALL_DIR%\code-reviewer.jar" %MAIN_CLASS% -C "%BIN_DIR%" .

if %ERRORLEVEL% neq 0 (
    echo.
    echo   [ERROR] Jar creation failed.
    pause
    exit /b %ERRORLEVEL%
)

echo   Copying supporting files...
echo Copying "%REPO_DIR%run-reviewer.bat" to "%INSTALL_DIR%\review.bat"
copy /y "%REPO_DIR%run-reviewer.bat" "%INSTALL_DIR%\review.bat"
dir "%INSTALL_DIR%\review.bat"
copy /y "%REPO_DIR%run-reviewer.bat" "%INSTALL_DIR%\review.bat" >nul
copy /y "%SRC_DIR%\pre-commit" "%INSTALL_DIR%\pre-commit" >nul
if exist "%REPO_DIR%.code-reviewer.properties" (
    copy /y "%REPO_DIR%.code-reviewer.properties" "%INSTALL_DIR%\.code-reviewer.properties" >nul
)

:: Copy PMD configuration
if exist "%REPO_DIR%config\pmd" (
    echo   [INFO] Copying PMD configuration...
    if not exist "%INSTALL_DIR%\config\pmd" mkdir "%INSTALL_DIR%\config\pmd"
    xcopy /s /y "%REPO_DIR%config\pmd\*" "%INSTALL_DIR%\config\pmd\" >nul
)

:: Automated PMD Binary Setup
echo   [INFO] Checking for PMD binaries...
if not exist "%INSTALL_DIR%\pmd-temp\pmd-bin-7.20.0\bin" (
    echo   [INFO] PMD not found. Downloading stable version 7.20.0...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$url='https://github.com/pmd/pmd/releases/download/pmd_releases/7.20.0/pmd-dist-7.20.0-bin.zip'; $zip='%INSTALL_DIR%\pmd.zip'; Invoke-WebRequest -Uri $url -OutFile $zip; Expand-Archive -Path $zip -DestinationPath '%INSTALL_DIR%\pmd-temp' -Force"

    if %ERRORLEVEL% equ 0 (
        echo   [SUCCESS] PMD binaries installed to %INSTALL_DIR%\pmd-bin
    ) else (
        echo   [WARNING] Automated PMD download failed. You may need to install it manually.
    )
)

:: Update the pre-commit hook in the install dir to use absolute path
powershell -NoProfile -Command "$path = 'C:\dev-tools\code-reviewer\review.bat'; $content = Get-Content -Path '%INSTALL_DIR%\pre-commit' -Raw; $content = $content -replace '\./run-reviewer\.bat', $path; [System.IO.File]::WriteAllText('%INSTALL_DIR%\pre-commit', $content)"

echo.
echo   +=========================================+
echo   ^|  Installation Complete!                 ^|
echo   +=========================================+
echo.
echo   Location: %INSTALL_DIR%
echo.
echo   QUICK START:
echo   ------------
echo   To enable the hook in any repository, run:
echo   copy "%INSTALL_DIR%\pre-commit" .git\hooks\pre-commit
echo.
pause
