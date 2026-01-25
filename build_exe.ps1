# build_exe.ps1
python -m pip install --upgrade pyinstaller

# Clean build
Remove-Item -Recurse -Force .\build -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .\dist  -ErrorAction SilentlyContinue

# Build
pyinstaller `
  --noconfirm `
  --onefile `
  --windowed `
  --name "TranscribeMate" `
  --add-data "assets\ffmpeg.exe;assets" `
  --add-data "assets\ffprobe.exe;assets" `
  app_gui.py
