@echo off
echo Configuring Windows Firewall for H4CKINTO Server...
echo.

REM Add inbound rule for port 9400
netsh advfirewall firewall add rule name="H4CKINTO Server" dir=in action=allow protocol=TCP localport=9400
if %errorlevel% equ 0 (
    echo ✓ Firewall rule added successfully
) else (
    echo ✗ Failed to add firewall rule. Please run as Administrator.
)

echo.
echo Configuration complete!
echo.
echo Your server will be accessible at:
echo - Local: http://localhost:9400
echo - Network: http://192.168.1.200:9400
echo.
pause
