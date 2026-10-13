@echo off
rem Creates/updates the Python venv in ..\.tools\venv with the tools from python-tools.txt.
rem Run it again any time to upgrade the tools. The pip cache goes to ..\.tmp.
setlocal
set "HERE=%~dp0"
for %%I in ("%HERE%..") do set "BASE=%%~fI"
set "VENV=%BASE%\.tools\venv"
set "PIP_CACHE_DIR=%BASE%\.tmp\pip-cache"
set "TEMP=%BASE%\.tmp"
set "TMP=%BASE%\.tmp"
if not exist "%TEMP%" mkdir "%TEMP%"

if not exist "%VENV%\Scripts\python.exe" (
    echo Creating venv in %VENV%
    python -m venv "%VENV%" || exit /b 1
)
"%VENV%\Scripts\python.exe" -m pip install --upgrade pip || exit /b 1
"%VENV%\Scripts\python.exe" -m pip install --upgrade -r "%HERE%python-tools.txt" || exit /b 1

rem Pre-download the GeoCLIP weights into ..\.tools\hf-cache so the first Photo Geolocation lookup
rem is fast. Skipped if geoclip is not installed.
set "HF_HOME=%BASE%\.tools\hf-cache"
set "TORCH_HOME=%BASE%\.tools\hf-cache"
set "TORCHINDUCTOR_CACHE_DIR=%BASE%\.tools\hf-cache\inductor"
if not exist "%HF_HOME%" mkdir "%HF_HOME%"
echo Warming up GeoCLIP model (one-time download, ~1-2 GB)...
"%VENV%\Scripts\python.exe" -c "from geoclip import GeoCLIP; GeoCLIP(); print('GeoCLIP ready')" || echo (GeoCLIP warm-up skipped. Photo Geolocation will download the model on first use.)

echo Done.
