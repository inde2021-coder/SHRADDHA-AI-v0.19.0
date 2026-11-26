$ErrorActionPreference = "Stop"

# Install Tesseract and both supported language packs for local OCR.
$ProgramFilesX86 = [Environment]::GetFolderPath("ProgramFilesX86")
$Candidates = @(
    (Join-Path $env:ProgramFiles "Tesseract-OCR\tesseract.exe"),
    (Join-Path $ProgramFilesX86 "Tesseract-OCR\tesseract.exe")
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

$Tesseract = $Candidates | Select-Object -First 1
if (-not $Tesseract) {
    $Winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $Winget) {
        throw "winget is unavailable. Install Windows App Installer, then rerun this script."
    }
    & $Winget.Source install --id UB-Mannheim.TesseractOCR --exact --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) {
        throw "Tesseract installation failed (winget exit code $LASTEXITCODE)."
    }
    $Tesseract = @(
        (Join-Path $env:ProgramFiles "Tesseract-OCR\tesseract.exe"),
        (Join-Path $ProgramFilesX86 "Tesseract-OCR\tesseract.exe")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
}
if (-not $Tesseract) {
    throw "Tesseract was not found in Program Files after installation."
}

$Tessdata = Join-Path (Split-Path -Parent $Tesseract) "tessdata"
New-Item -ItemType Directory -Force -Path $Tessdata | Out-Null
$Base = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main"

foreach ($Language in @("eng", "hin")) {
    $Destination = Join-Path $Tessdata "$Language.traineddata"
    $Temporary = "$Destination.download"
    if (-not (Test-Path -LiteralPath $Destination) -or (Get-Item -LiteralPath $Destination).Length -lt 100000) {
        try {
            Invoke-WebRequest -Uri "$Base/$Language.traineddata" -OutFile $Temporary
            if ((Get-Item -LiteralPath $Temporary).Length -lt 100000) {
                throw "Downloaded $Language trained-data file is incomplete."
            }
            Move-Item -LiteralPath $Temporary -Destination $Destination -Force
        }
        finally {
            Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

$env:TESSDATA_PREFIX = $Tessdata
& $Tesseract --list-langs
if ($LASTEXITCODE -ne 0) {
    throw "Tesseract could not load its language data."
}
Write-Host ""
Write-Host "Shraddha local OCR setup completed successfully."
Write-Host "Engine: $Tesseract"
Write-Host "Language packs: English (eng), Hindi (hin)"
Write-Host "Restart Shraddha AI PC Companion if it is already running."
