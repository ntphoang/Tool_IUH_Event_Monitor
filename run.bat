@echo off
cd /d D:\IUHEventMonitor

set "IUH_COOKIE=IUH_COOKIE=_ga=GA1.3.99108605.1710310004; PHPSESSID=82t35cl6ok0p2t00ocdo675g1r"
set "MAIL_USERNAME=nguyentranphihoang2005@gmail.com"
set "MAIL_APP_PASSWORD=syoa ntdx febu heay"
set "MAIL_TO=nguyentranphihoang2005@gmail.com"

"C:\Program Files\Java\jdk-17\bin\java.exe" -jar "target\IUHEventMonitor-1.0-SNAPSHOT.jar"

pause