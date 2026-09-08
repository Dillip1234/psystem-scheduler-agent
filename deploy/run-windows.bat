@echo off
REM Simple launcher for running the agent as a background process on a customer Windows PC.
REM For a real production install, wrap this with NSSM (https://nssm.cc) to run as a proper
REM Windows Service, or use Task Scheduler with trigger "At startup" and this script as the action.

set PSYSTEM_HOME=C:\psystem-agent
set JAVA_HOME=C:\Program Files\Java\jdk-17

"%JAVA_HOME%\bin\java.exe" -jar "%PSYSTEM_HOME%\psystem-scheduler-agent.jar" ^
  --spring.config.additional-location=file:%PSYSTEM_HOME%\application-customer.yml
