@echo off
java -XX:SharedArchiveFile="%~dp0build\jolt.jsa" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xms32m -cp "%~dp0build\jolt.jar" dev.jolt.Main %*
