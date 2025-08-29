# PowerShell script to set up Java environment
$env:JAVA_HOME = "C:\Users\Admin\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.15.6-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Write-Host "Java environment set up" -ForegroundColor Green
Write-Host "JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Yellow
Write-Host "Java version:" -ForegroundColor Yellow
& "$env:JAVA_HOME\bin\java.exe" -version

Write-Host "`nNow you can run: .\gradlew clean" -ForegroundColor Green
