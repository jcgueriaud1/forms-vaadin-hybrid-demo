@echo off
rem Start the demo's Forms client on Windows with the Forms Standalone Launcher
rem (FSAL) on Java 21+. UNTESTED: please report results in issue #1.
rem
rem   run-forms-client.cmd
rem   set JAVA_HOME=C:\path\to\jdk-21 & run-forms-client.cmd
rem
rem Uses %JAVA_HOME%\bin\java if JAVA_HOME is set, else java on the PATH.
rem Start hello-view first (localhost:8080).
setlocal
if "%HYBRID_FORMS_URL%"=="" set HYBRID_FORMS_URL=http://localhost:7777/forms
set JAVA=java
if not "%JAVA_HOME%"=="" set JAVA="%JAVA_HOME%\bin\java"
set DIR=%TEMP%\fsal
if not exist "%DIR%" mkdir "%DIR%"

rem FSAL caches archive jars by name and keeps using a cached hybrid-bean.jar
rem after it is redeployed, so drop the cached copy (frmall.jar stays cached).
if exist "%TEMP%\frmsal" del /s /q "%TEMP%\frmsal\hybrid-bean.jar" >nul 2>&1
rem Always fetch frmsal.jar from the server, so it matches its Forms version.
curl -sf -o "%DIR%\frmsal.jar" "%HYBRID_FORMS_URL%/java/frmsal.jar" || (
  echo Cannot download frmsal.jar from %HYBRID_FORMS_URL% & exit /b 1
)
%JAVA% -version
%JAVA% --add-opens java.desktop/sun.awt=ALL-UNNAMED %JAVA_OPTS% -jar "%DIR%\frmsal.jar" ^
  -url "%HYBRID_FORMS_URL%/frmservlet?config=hybrid" -t 10000
