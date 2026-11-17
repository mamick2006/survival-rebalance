@echo off
rem Survival Rebalance: writes customize.txt in the SurvivalRebalance folder, or adds the changes that are new since it was written.
rem It lives in the app folder; the install root (customize.txt, game_dir.txt, logs) is the folder above.
setlocal
set "APP=%~dp0"
set "APP=%APP:~0,-1%"
set "TOOL=%APP%"
for %%I in ("%APP%") do if /i "%%~nxI"=="app" for %%J in ("%APP%\..") do set "TOOL=%%~fJ"
set "GAME="
for %%I in ("%TOOL%\..") do set "PARENT=%%~fI"
if exist "%PARENT%\mods\" if exist "%PARENT%\jre\bin\java.exe" set "GAME=%PARENT%"
if not defined GAME if exist "%TOOL%\game_dir.txt" set /p GAME=<"%TOOL%\game_dir.txt"
if defined GAME set "GAME=%GAME:"=%"
set "JAVA=java"
if defined GAME if exist "%GAME%\jre\bin\java.exe" set "JAVA=%GAME%\jre\bin\java.exe"
rem Java cannot start from a path with a ; in it, or with letters outside this Windows' code page:
rem say so in one plain line instead of a raw Java error.
if not "%APP:;=%"=="%APP%" goto badpath
if /i "%JAVA%"=="java" where java >nul 2>nul || goto nojre
if exist "%APP%\SurvivalRebalance.jar" "%JAVA%" -jar "%APP%\SurvivalRebalance.jar" version >nul 2>nul || goto nojava
"%JAVA%" -jar "%APP%\SurvivalRebalance.jar" customize --tool-dir "%APP%" %*
set "RC=%ERRORLEVEL%"
echo.
pause
exit /b %RC%

:nogame
echo Cannot find the Starsector folder.
echo Put the SurvivalRebalance folder inside the Starsector folder, next to "mods".
echo Or write the path of the Starsector folder into game_dir.txt in the SurvivalRebalance folder.
echo.
pause
exit /b 2

:badpath
echo Survival Rebalance cannot run from this folder, because its path has a ; in it. Move the Starsector folder to a folder whose path has only plain letters, numbers and spaces, such as C:\Games\Starsector, then try again.
echo.
pause
exit /b 2

:nojava
rem Java could not run the jar. Run it again from inside the jar's folder, so the path Java is given
rem has only plain letters. The game's own Java starting means the Starsector folder's path is fine,
rem so if the jar runs that way, the tool folder's path is what Java cannot read.
pushd "%APP%"
"%JAVA%" -version >nul 2>nul || goto nojvm
"%JAVA%" -jar SurvivalRebalance.jar version >nul 2>nul || goto javafail
popd
echo Survival Rebalance cannot run from "%TOOL%", because the path of that folder has letters that Java cannot read on this Windows. Rename the folder, or move it, so that its path has only plain letters, numbers and spaces, then try again.
echo.
pause
exit /b 2

:nojvm
popd
if /i "%JAVA%"=="java" goto javafail
echo The Java in the Starsector folder cannot start: "%JAVA%". Most likely the path of the Starsector folder has letters that Java cannot read on this Windows. Move the Starsector folder to a folder whose path has only plain letters, numbers and spaces, such as C:\Games\Starsector, then try again. If its path is plain already, install Starsector again.
echo.
pause
exit /b 2

:javafail
popd
echo Java could not start Survival Rebalance. Either the tool's files are damaged, or this Java is older than Java 17: "%JAVA%". Unzip SurvivalRebalance.zip into the Starsector folder again. If that does not help, install Starsector again: the tool runs on the game's own Java.
echo.
pause
exit /b 2

:nojre
if not defined GAME goto nogame
echo Cannot find Java. Survival Rebalance runs on the Java that comes with Starsector, and "%GAME%" has none in jre\bin. Check that this is your Starsector folder, or install Starsector again.
echo.
pause
exit /b 2
