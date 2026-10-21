#!/system/bin/sh
export CLASSPATH="$1"
exec /system/bin/app_process / com.gdhapp.client.testing.BulkTransferProbe "$2" "$3"
