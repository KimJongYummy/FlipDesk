@echo off
rem Optional: builds a standalone dist\FlipDesk.exe (no Python needed on the PC) with PyInstaller.
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (py -3 -m venv .venv || python -m venv .venv)
".venv\Scripts\python.exe" -m pip install -r requirements.txt pyinstaller || exit /b 1
rem A venv's tkinter can't find Tcl/Tk, so PyInstaller would silently leave tkinter out and the exe
rem would crash on start. Point it at the base Python's Tcl/Tk.
for /f "usebackq delims=" %%p in (`.venv\Scripts\python.exe -c "import sys; print(sys.base_prefix)"`) do set "PYBASE=%%p"
if not defined TCL_LIBRARY set "TCL_LIBRARY=%PYBASE%\tcl\tcl8.6"
if not defined TK_LIBRARY set "TK_LIBRARY=%PYBASE%\tcl\tk8.6"
".venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean --onefile --windowed --name FlipDesk ^
  --collect-submodules winrt --collect-submodules pycaw --collect-submodules flipdesk ^
  --icon NONE FlipDesk.pyw || exit /b 1
findstr /c:"missing module named tkinter" build\FlipDesk\warn-FlipDesk.txt >nul && (echo tkinter was left out of the exe & exit /b 1)
echo.
echo Built %~dp0dist\FlipDesk.exe
