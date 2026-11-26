$ErrorActionPreference = "Stop"

# Install the Windows Tesseract engine if it is not already present.
$Candidates = @(
    "$env:ProgramFiles\Tesseract-OCR\tesseract.exe",
    "$env:ProgramFiles(x86)\Tesseract-OCR\tesseract.exe"
)
$Tesseract = $Candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $Tesseract) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "Windows Package Manager (winget) is required. Install Tesseract OCR, then rerun this script."
    }
    winget install --id UB-Mannheim.TesseractOCR --exact --silent --accept-package-agreements --accept-source-agreements
    $Tesseract = $Candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $Tesseract) {
    throw "Tesseract installation was not found in the standard Program Files location."
}

$Tessdata = Join-Path (Split-Path $Tesseract -Parent) "tessdata"
New-Item -ItemType Directory -Force -Path $Tessdata | Out-Null
$Base = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main"
foreach ($Language in @("eng", "hin")) {
    $Destination = Join-Path $Tessdata "$Language.traineddata"
    if (-not (Test-Path $Destination) -or (Get-Item $Destination).Length -lt 100000) {
        Invoke-WebRequest -Uri "$Base/$Language.traineddata" -OutFile "$Destination.tmp"
        if ((Get-Item "$Destination.tmp").Length -lt 100000) {
            Remove-Item "$Destination.tmp" -Force -ErrorAction SilentlyContinue
            throw "Downloaded $Language OCR data is incomplete."
        }
        Move-Item "$Destination.tmp" $Destination -Force
    }
}
Write-Host "Shraddha OCR setup complete. Tesseract: $Tesseract"
Write-Host "Installed language packs: English (eng), Hindi (hin)"
