$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
Push-Location frontend
npm ci
if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
npm run build
if ($LASTEXITCODE -ne 0) { throw "Frontend build failed" }
Pop-Location
New-Item -ItemType Directory -Force backend/src/main/resources/static | Out-Null
Copy-Item -Recurse -Force frontend/dist/* backend/src/main/resources/static/
mvn -B -f backend/pom.xml package
if ($LASTEXITCODE -ne 0) { throw "Backend build failed" }
Write-Host "Run: java -jar backend/target/docmind-1.0.0.jar"
