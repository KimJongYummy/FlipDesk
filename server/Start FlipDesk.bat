@echo off
rem FlipDesk server: one-click start. First run creates a virtual environment.
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\pythonw.exe" (
  echo Setting up FlipDesk for the first time...
  where py >nul 2>nul && (py -3 -m venv .venv) || (python -m venv .venv)
  if errorlevel 1 (echo Python 3.11+ is required: https://www.python.org/downloads/ & pause & exit /b 1)
  ".venv\Scripts\python.exe" -m pip install --upgrade pip
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
  if errorlevel 1 (echo Installing dependencies failed. & pause & exit /b 1)
)
rem Runs in the system tray (look for the FlipDesk icon near the clock).
start "" ".venv\Scripts\pythonw.exe" "%~dp0FlipDesk.pyw" %*
endlocal
