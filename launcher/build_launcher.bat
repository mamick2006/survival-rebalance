@echo off
rem Builds SurvivalRebalance.exe from SurvivalRebalance.cs with the C# compiler that ships with
rem Windows (.NET Framework 4). Output: launcher\build\SurvivalRebalance.exe
setlocal
cd /d "%~dp0"
set "CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if not exist "%CSC%" set "CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe"
if not exist "%CSC%" (
  echo Cannot find csc.exe from the .NET Framework 4 in %WINDIR%\Microsoft.NET.
  exit /b 1
)
if not exist build mkdir build
"%CSC%" /nologo /target:winexe /platform:anycpu /optimize+ /win32icon:icon.ico /out:build\SurvivalRebalance.exe /reference:System.Windows.Forms.dll SurvivalRebalance.cs || exit /b 1
echo built launcher\build\SurvivalRebalance.exe
