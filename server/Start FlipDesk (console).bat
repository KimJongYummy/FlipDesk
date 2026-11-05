@echo off
rem Runs the server in this console window with logs (Ctrl+C to stop).
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" call "Start FlipDesk.bat" --no-tray
".venv\Scripts\python.exe" -m flipdesk --no-tray %*
