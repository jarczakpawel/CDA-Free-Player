$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)
python -m pip install --disable-pip-version-check -r requirements.txt -r requirements-dev.txt
if ($LASTEXITCODE -ne 0) { throw "Dependency installation failed: $LASTEXITCODE" }
python -m cda_free_player.selftest --contracts
if ($LASTEXITCODE -ne 0) { throw "Source contract self-test failed: $LASTEXITCODE" }
python -m PyInstaller --noconfirm --clean --windowed --name "CDA Free Player" `
  --icon "assets\cda-free-player.ico" `
  --add-data "assets;assets" `
  --add-data "VERSION;." `
  --hidden-import "PIL._tkinter_finder" `
  --hidden-import "PIL._imagingtk" `
  --collect-data "webview" `
  --collect-all "pythonnet" `
  --collect-all "clr_loader" `
  --hidden-import "webview.platforms.winforms" `
  --hidden-import "webview.platforms.edgechromium" `
  --exclude-module "webview.platforms.android" `
  --exclude-module "webview.platforms.gtk" `
  --exclude-module "webview.platforms.qt" `
  --exclude-module "webview.platforms.cocoa" `
  --exclude-module "webview.platforms.winui3" `
  --exclude-module "webview.platforms.cef" `
  desktop_entry.py
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed: $LASTEXITCODE" }

$mpvRelease = '20260921'
$mpvAsset = 'mpv-x86_64-20260921-git-e76a35ec95.7z'
$expectedHash = 'edff63899c74c0c11fd49233df8a2979a07f9814f5752a2f4012f405562d49d1'
$mpvUrl = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$mpvRelease/$mpvAsset"
$archive = Join-Path $PWD 'build\mpv.7z'
$unpack = Join-Path $PWD 'build\mpv-runtime'
if (Test-Path $unpack) { Remove-Item $unpack -Recurse -Force }
Invoke-WebRequest -Uri $mpvUrl -OutFile $archive
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ine $expectedHash) { throw 'mpv SHA-256 mismatch' }
$sevenZip = (Get-Command 7z -ErrorAction Stop).Source
& $sevenZip x -y "-o$unpack" $archive | Out-Null
if ($LASTEXITCODE -ne 0) { throw "mpv extraction failed: $LASTEXITCODE" }
$mpvFiles = @(Get-ChildItem $unpack -Recurse -Filter mpv.exe)
if ($mpvFiles.Count -ne 1) { throw 'mpv.exe missing or ambiguous' }
$mpvTarget = Join-Path $PWD 'dist\CDA Free Player\mpv'
New-Item -ItemType Directory -Force $mpvTarget | Out-Null
Copy-Item (Join-Path $mpvFiles[0].DirectoryName '*') $mpvTarget -Recurse -Force
@{ release = $mpvRelease; asset = $mpvUrl; sha256 = $expectedHash } |
    ConvertTo-Json | Set-Content (Join-Path $mpvTarget 'build-source.json') -Encoding utf8
& (Join-Path $mpvTarget 'mpv.exe') --version
if ($LASTEXITCODE -ne 0) { throw "Bundled mpv failed: $LASTEXITCODE" }
