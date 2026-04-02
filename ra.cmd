@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%"

echo [ra] Cleaning and installing latest workspace modules for taskpilot-app...
call "%SCRIPT_DIR%mvnw.cmd" -f "%SCRIPT_DIR%pom.xml" -pl taskpilot-app -am -DskipTests clean install
if errorlevel 1 (
	set "EXIT_CODE=%ERRORLEVEL%"
	popd
	exit /b %EXIT_CODE%
)

echo [ra] Starting TaskPilotApplication...
call "%SCRIPT_DIR%mvnw.cmd" -f "%SCRIPT_DIR%pom.xml" -pl taskpilot-app spring-boot:run -Dspring-boot.run.main-class=com.taskpilot.app.TaskPilotApplication %*
set "EXIT_CODE=%ERRORLEVEL%"

popd
exit /b %EXIT_CODE%
