@echo off
rem Optional: builds a standalone dist\FlipDesk.exe (no Python needed on the PC) with PyInstaller.
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (py -3 -m venv .venv || python -m venv .venv)
".venv\Scripts\python.exe" -m pip install -r requirements.txt pyinstaller || exit /b 1
".venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean --onefile --windowed --name FlipDesk ^
  --collect-submodules winrt --collect-submodules pycaw --collect-submodules flipdesk ^
  --icon NONE FlipDesk.pyw || exit /b 1
echo.
echo Built %~dp0dist\FlipDesk.exe
