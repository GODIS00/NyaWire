#!/system/bin/sh
printf 'NyaWire-UDP-PROBE' | /system/bin/toybox timeout 5 /system/bin/toybox nc -u -w 5 -W 2 10.0.2.2 18081
