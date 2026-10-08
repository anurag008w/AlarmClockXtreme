#!/bin/sh
# On-device check of the external alert receiver (debug build). Results go to $1/alert_*.txt and alert_active.png.
OUT=$1
P=com.sysadmindoc.alarmclock.debug
cat > /tmp/ep.xml <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="enabled" value="true" /><string name="token">ABCDEFGHJKMN</string></map>
X
adb push /tmp/ep.xml /data/local/tmp/ep.xml
adb shell chmod 644 /data/local/tmp/ep.xml
adb shell am force-stop $P
adb shell "run-as $P sh -c 'mkdir -p shared_prefs && cat /data/local/tmp/ep.xml > shared_prefs/external_alert.xml'" || true
B="am broadcast -n $P/com.sysadmindoc.alarmclock.receiver.ExternalAlertReceiver -a com.alarmclockxtreme.action.EXTERNAL_ALERT"
adb shell "$B --es alert_id bad1 --es token WRONGCODE000 --es level ring" > $OUT/alert_bad_out.txt 2>&1
sleep 3
adb shell dumpsys activity services $P | grep -c ExternalAlertService > $OUT/alert_bad_services.txt
adb shell "$B --es alert_id good1 --es token ABCD-EFGH-JKMN --es level ring --es message 'Divya test' --ei duration_s 20" > $OUT/alert_good_out.txt 2>&1
sleep 5
adb exec-out screencap -p > $OUT/alert_active.png
adb shell dumpsys activity services $P | grep -c ExternalAlertService > $OUT/alert_good_services.txt
adb shell dumpsys notification --noredact | grep -c external_alert_channel > $OUT/alert_notification.txt
adb shell dumpsys vibrator_manager 2>/dev/null | head -40 > $OUT/alert_vibrator.txt
adb shell cmd statusbar expand-notifications
sleep 2
python3 scripts/tap_text.py STOP > $OUT/alert_tap_out.txt 2>&1
sleep 3
adb shell dumpsys activity services $P | grep -c ExternalAlertService > $OUT/alert_after_stop_services.txt
adb shell cmd statusbar collapse
sleep 15
adb shell "$B --es alert_id good2 --es token ABCDEFGHJKMN --es level vibrate --ei duration_s 5" > $OUT/alert_good2_out.txt 2>&1
sleep 3
adb shell dumpsys activity services $P | grep -c ExternalAlertService > $OUT/alert_second_running.txt
sleep 10
adb shell dumpsys activity services $P | grep -c ExternalAlertService > $OUT/alert_second_after_expiry.txt
exit 0
