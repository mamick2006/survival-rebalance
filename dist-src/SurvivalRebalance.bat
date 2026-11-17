@echo off
rem Survival Rebalance: opens the window, like SurvivalRebalance.exe. Use it when Windows or an
rem antivirus will not start the exe. Like the exe, it first installs a downloaded update.
setlocal
echo Survival Rebalance is opening. Keep this window open until you close Survival Rebalance.
set "TOOL=%~dp0"
set "TOOL=%TOOL:~0,-1%"
set "GAME="
for %%I in ("%TOOL%\..") do set "PARENT=%%~fI"
if exist "%PARENT%\mods\" set "GAME=%PARENT%"
if not defined GAME if exist "%TOOL%\game_dir.txt" set /p GAME=<"%TOOL%\game_dir.txt"
if defined GAME set "GAME=%GAME:"=%"
set "JAVA="
if defined GAME if exist "%GAME%\jre\bin\java.exe" set "JAVA=%GAME%\jre\bin\java.exe"
if not defined JAVA where java >nul 2>nul && set "JAVA=java"
if not defined JAVA (
  echo No Java found. Put this folder inside your Starsector folder, which has its own Java, and start it again.
  pause
  exit /b 1
)
set "GAMEARG="
if defined GAME set GAMEARG=--game "%GAME%"
set "JAR=%TOOL%\app\SurvivalRebalance.jar"
if not exist "%JAR%" set "JAR=%TOOL%\SurvivalRebalance.jar"
rem Java cannot start from a path with a ; in it, or with letters outside this Windows' code page:
rem say so in one plain line instead of a raw Java error.
if not "%TOOL:;=%"=="%TOOL%" goto badpath
for %%I in ("%JAR%") do set "JARDIR=%%~dpI"
if exist "%JAR%" "%JAVA%" -jar "%JAR%" version >nul 2>nul || goto nojava
:start
call :install
set "JAR=%TOOL%\app\SurvivalRebalance.jar"
if not exist "%JAR%" set "JAR=%TOOL%\SurvivalRebalance.jar"
"%JAVA%" -cp "%JAR%" survivalrebalance.gui.Window %GAMEARG% --launcher
set "RC=%ERRORLEVEL%"
if "%RC%"=="42" goto start
if not "%RC%"=="0" (
  echo.
  echo The window closed with error code %RC%. The lines above say why.
  pause
)
exit /b %RC%


:badpath
echo Survival Rebalance cannot run from this folder, because its path has a ; in it. Move the Starsector folder to a folder whose path has only plain letters, numbers and spaces, such as C:\Games\Starsector, then try again.
echo.
pause
exit /b 2

:nojava
rem Java could not run the jar. Run it again from inside the jar's folder, so the path Java is given
rem has only plain letters. The game's own Java starting means the Starsector folder's path is fine,
rem so if the jar runs that way, the tool folder's path is what Java cannot read.
pushd "%JARDIR%"
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

:install
rem A downloaded update is complete when update\update.json exists. Copy it in (never the
rem player's files), delete what the new version no longer ships, and let the new jar check it.
set "UD=%TOOL%\update"
if not exist "%UD%\update.json" (
  if exist "%UD%\staged" rmdir /s /q "%UD%\staged"
  exit /b 0
)
echo Installing the downloaded update...
robocopy "%UD%\staged" "%TOOL%" /E /R:5 /W:1 /XF customize.txt game_dir.txt disabled_changes.txt /XD logs /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
  echo The new files could not be copied. Close any program that has a file of this folder open, then start again.
  pause
  exit /b 1
)
if exist "%UD%\remove.txt" for /f "usebackq delims=" %%F in ("%UD%\remove.txt") do call :remove "%%F"
set "NEWJAR=%TOOL%\app\SurvivalRebalance.jar"
if not exist "%NEWJAR%" set "NEWJAR=%TOOL%\SurvivalRebalance.jar"
"%JAVA%" -jar "%NEWJAR%" update --finish
if errorlevel 1 pause
exit /b 0

:remove
rem update --finish removes these too; this only skips the player's files and odd paths
set "R=%~1"
set "R=%R:/=\%"
echo \%R%| findstr /i /c:".." /c:":" /c:"\customize.txt" /c:"\game_dir.txt" /c:"\disabled_changes.txt" /c:"\logs\" >nul && exit /b 0
if /i "%R:~0,7%"=="update\" exit /b 0
if exist "%TOOL%\%R%" del /f /q "%TOOL%\%R%"
exit /b 0
