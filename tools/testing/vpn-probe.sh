#!/system/bin/sh
printf 'GET /health HTTP/1.1\r\nHost: 10.0.2.2\r\nConnection: close\r\n\r\n' | /system/bin/toybox nc -w 7 10.0.2.2 18080
