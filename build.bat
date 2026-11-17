@echo off
rem Survival Rebalance build: compile the tool and the monitor mod, run their tests, and write
rem dist\SurvivalRebalance\ and dist\SurvivalRebalance.zip. SurvivalRebalance.exe is built from
rem launcher\SurvivalRebalance.cs with the C# compiler that ships with Windows.
rem
rem Needs JDK 17 or later. Set JAVA_HOME to it, or put javac, java and jar on the PATH.
rem Set STARSECTOR to your Starsector folder: the monitor compiles against the game's jars in
rem starsector-core (read only). The patches folder already holds the sealed patch files.
rem SR_TESTED_GAME is the game version the patch set was tested on; the manifest records it.
setlocal
cd /d "%~dp0"
if not defined SR_TESTED_GAME set "SR_TESTED_GAME=0.98.5a-RC7"
if not defined STARSECTOR (
    echo ERROR: set STARSECTOR to your Starsector folder, for example
    echo    set STARSECTOR=C:\Games\Starsector
    exit /b 1
)
set "CORE=%STARSECTOR%\starsector-core"
if not exist "%CORE%\starfarer.api.jar" (
    echo ERROR: starfarer.api.jar not found in %CORE%
    exit /b 1
)
if defined JAVA_HOME (set "JAVAC=%JAVA_HOME%\bin\javac.exe" & set "JAVA=%JAVA_HOME%\bin\java.exe" & set "JAR=%JAVA_HOME%\bin\jar.exe") else (set "JAVAC=javac" & set "JAVA=java" & set "JAR=jar")
set "CP=lib\asm-9.7.1.jar;lib\asm-tree-9.7.1.jar"

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes build\test build\mon\classes build\mon\test dist

rem The tool.
dir /s /b src\*.java > build\sources.txt
"%JAVAC%" --release 17 -encoding UTF-8 -cp "%CP%" -d build\classes @build\sources.txt || goto fail
copy /y src\survivalrebalance\gui\icon.png build\classes\survivalrebalance\gui\ >nul || goto fail
copy /y src\survivalrebalance\history.txt build\classes\survivalrebalance\ >nul || goto fail
call launcher\build_launcher.bat || goto fail
pushd build\classes
"%JAR%" xf ..\..\lib\asm-9.7.1.jar org || (popd & goto fail)
"%JAR%" xf ..\..\lib\asm-tree-9.7.1.jar org || (popd & goto fail)
popd
"%JAR%" --create --file build\SurvivalRebalance.jar --main-class survivalrebalance.Main -C build\classes . || goto fail
dir /s /b test\*.java > build\tests.txt
"%JAVAC%" --release 17 -encoding UTF-8 -cp build\classes -d build\test @build\tests.txt || goto fail
"%JAVA%" -cp "build\test;build\classes" survivalrebalance.Tests || goto fail

rem The monitor mod.
set "MON=build\mon\Survival Rebalance Monitor"
dir /s /b monitor\src\*.java > build\mon\sources.txt
"%JAVAC%" --release 17 -encoding UTF-8 -cp "%CORE%\*" -d build\mon\classes @build\mon\sources.txt || goto fail
dir /s /b monitor\test\*.java > build\mon\tests.txt
"%JAVAC%" --release 17 -encoding UTF-8 -cp "build\mon\classes;%CORE%\json.jar" -d build\mon\test @build\mon\tests.txt || goto fail
"%JAVA%" -cp "build\mon\test;build\mon\classes;%CORE%\json.jar" survivalrebalance.monitor.CheckTest monitor\test\fixtures || goto fail
mkdir "%MON%\jars"
"%JAR%" cf "%MON%\jars\SurvivalRebalanceMonitor.jar" -C build\mon\classes . || goto fail
xcopy /e /i /q /y monitor\mod "%MON%" >nul || goto fail

rem The release folder and zip: the exe, its fallback .bat and .sh, the README and shipped.txt at
rem the top, everything else in app\.
set "S=dist\SurvivalRebalance"
set "A=%S%\app"
mkdir "%A%\patches"
copy /y launcher\build\SurvivalRebalance.exe "%S%" >nul || goto fail
for %%f in (SurvivalRebalance.bat SurvivalRebalance.sh) do copy /y dist-src\%%f "%S%" >nul || goto fail
for %%f in (apply undo status check customize update) do copy /y dist-src\%%f.bat "%A%" >nul || goto fail
copy /y lib\ASM-LICENSE.txt "%A%" >nul
copy /y build\SurvivalRebalance.jar "%A%" >nul
copy /y patches\*.json "%A%\patches" >nul || goto fail
xcopy /e /i /q /y "%MON%" "%A%\Survival Rebalance Monitor" >nul || goto fail
"%JAVA%" -jar build\SurvivalRebalance.jar manifest --patches patches --game-version "%SR_TESTED_GAME%" --checked checked_mods.json --out "%A%\manifest.json" --index "%A%\Survival Rebalance Monitor\data\config\survival_rebalance_index.json" || goto fail
"%JAVA%" -jar build\SurvivalRebalance.jar supported --manifest "%A%\manifest.json" --readme dist-src\README.txt --out "%S%\README.txt" || goto fail
"%JAVA%" -jar build\SurvivalRebalance.jar shiplist --out "%S%" || goto fail
"%JAVA%" -jar build\SurvivalRebalance.jar bundle --stage "%S%" --jar "%A%\SurvivalRebalance.jar" || goto fail
"%JAR%" --create --no-manifest --file dist\SurvivalRebalance.zip -C dist SurvivalRebalance || goto fail
echo built dist\SurvivalRebalance.zip
exit /b 0

:fail
echo BUILD FAILED
exit /b 1
