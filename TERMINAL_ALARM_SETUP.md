# AlarmClockXtreme — Terminal Alarm Control

This file documents the supported terminal-to-cloud-to-Android workflow.

## What is the source of truth?

The Android app is still authoritative for actual scheduling and alarm firing on the phone.

The terminal and web UI are cloud control surfaces:

`terminal -> Render cloud API -> GitHub data repo -> Android cloud sync -> Room -> Android scheduler`

The persistent GitHub data repository is:

`anurag008w/smartrotator-data`

under:

`alarmclockxtreme/`

Do not edit generated Render filesystem data directly. The cloud service pulls the GitHub dataset at startup and periodically reconciles it.

## Android cloud URL

The Android build uses this default base URL:

`https://alarmclockxtreme-cloud.onrender.com/`

It is compiled into `BuildConfig.CLOUD_BASE_URL` unless a Gradle `cloudBaseUrl` property overrides it.

## 1. Terminal setup

The CLI needs only Python 3.10+ standard-library modules.

From the repository root:

```bash
python3 scripts/alarmctl.py login
```

Or create a new account:

```bash
python3 scripts/alarmctl.py register
```

The JWT is saved locally at:

`~/.config/alarmclockxtreme/token.json`

The password is never stored by the CLI.

You can override the token path with `ACX_TOKEN_FILE` or provide a token directly with `ACX_TOKEN`.

## 2. Create an alarm from the terminal

Simple example:

```bash
python3 scripts/alarmctl.py create \
  --time 06:30 \
  --label "JEE Study" \
  --days MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY \
  --challenge MATH_EASY \
  --set volume=85 \
  --set gradualVolumeSeconds=60 \
  --set snoozeDurationMinutes=5 \
  --set maxSnoozeCount=1
```

One-time alarm:

```bash
python3 scripts/alarmctl.py create \
  --time 07:00 \
  --label "Exam Day" \
  --set specificDate=2026-10-20
```

Disabled alarm:

```bash
python3 scripts/alarmctl.py create \
  --time 08:00 \
  --label "Later" \
  --disabled
```

The CLI creates a new cloud alarm ID and uses `expectedVersion=0`, so creation is compatible with the cloud concurrency rules.

## 3. Use a full JSON alarm payload

For complete control, put the exact Android-compatible alarm fields in a JSON file:

```json
{
  "hour": 6,
  "minute": 30,
  "label": "JEE Study",
  "isEnabled": true,
  "repeatDays": [
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY"
  ],
  "ringtoneUri": "",
  "vibrationEnabled": true,
  "vibrationIntensity": 2,
  "volume": 90,
  "overrideSystemVolume": true,
  "gradualVolumeSeconds": 60,
  "snoozeDurationMinutes": 5,
  "maxSnoozeCount": 1,
  "showOnLockScreen": true,
  "challengeType": "MATH_EASY",
  "group": "Study",
  "flashWake": false,
  "vibrationPattern": "escalating",
  "ttsEnabled": true,
  "smartAlarmEnabled": false,
  "smartAlarmWindowMinutes": 30,
  "skipOnHolidays": false,
  "nfcTagId": "",
  "barcodeValue": "",
  "spotifyUri": "",
  "hueEnabled": false,
  "huePreWakeMinutes": 30,
  "photoMatchUri": "",
  "challengeChain": "MATH_EASY,SHAKE",
  "progressiveSnooze": false,
  "backupSoundEnabled": true,
  "backupSoundDelaySec": 40,
  "sunriseSimulation": false,
  "sunriseMinutes": 15,
  "specificDate": "",
  "profileName": "Morning Study",
  "earlyDismissMinutes": 0,
  "guardianEnabled": false,
  "guardianPhone": "",
  "guardianDelaySec": 300,
  "locationDismissEnabled": false,
  "locationDismissLat": 0,
  "locationDismissLng": 0,
  "locationDismissRadius": 100,
  "wifiDismissSsid": "",
  "internetRadioUrl": "",
  "flashlightStrobe": false,
  "morningRoutine": "Drink water\nOpen study desk\nStart revision",
  "hardwareButtonAction": "NONE",
  "dismissAtRingtoneEnd": false,
  "holdToDismissEnabled": true,
  "ringtonePool": "",
  "solarOffsetMinutes": 0,
  "solarAnchor": "SUNRISE",
  "vibrationDelaySeconds": 10,
  "weatherEarlyMinutes": 0,
  "requiredSquats": 10,
  "dismissActionType": "NONE",
  "dismissActionPayload": "",
  "firingBackgroundImageEnabled": false,
  "firingBackgroundImageUri": "",
  "firingBackgroundBlurEnabled": true,
  "sortOrder": 0,
  "shiftPattern": "",
  "shiftPatternStartDate": "",
  "timezonePolicy": "LOCAL",
  "fixedTimezoneId": ""
}
```

Create it:

```bash
python3 scripts/alarmctl.py create --json alarm.json
```

You can also pipe JSON through stdin:

```bash
cat alarm.json | python3 scripts/alarmctl.py create --json -
```

## 4. List alarms

```bash
python3 scripts/alarmctl.py list
```

The list shows cloud ID, time, enabled state, label, repeat days, and cloud version.

## 5. Update an existing alarm safely

The CLI first reads the latest cloud version, merges your requested changes into the existing payload, and sends the expected version.

Change only volume:

```bash
python3 scripts/alarmctl.py update ALARM_ID --set volume=70
```

Change label and enable it:

```bash
python3 scripts/alarmctl.py update ALARM_ID \
  --set label="Deep Work" \
  --set isEnabled=true
```

Use a JSON patch:

```json
{
  "smartAlarmEnabled": true,
  "smartAlarmWindowMinutes": 20,
  "ttsEnabled": true
}
```

Then:

```bash
python3 scripts/alarmctl.py update ALARM_ID --json patch.json
```

A stale update is rejected by the server instead of silently overwriting a newer web/device edit.

## 6. Delete safely

```bash
python3 scripts/alarmctl.py delete ALARM_ID
```

Deletion creates a cloud tombstone. Android will remove the corresponding local alarm on sync and will not resurrect the deleted alarm from stale local state.

## 7. Force the running cloud service to pull GitHub data

```bash
python3 scripts/alarmctl.py pull
```

This calls `POST /api/sync/refresh`.

The endpoint:

1. Detects unsynced changes on the running cloud filesystem.
2. Pushes them first so they are not discarded.
3. Pulls the latest persistent GitHub dataset.
4. Returns sync status.

This is also what the web app uses before it displays alarm data after login / session restore.

## 8. Check account and cloud health

```bash
python3 scripts/alarmctl.py status
```

## 9. Can the terminal configure everything the web can?

Yes for the cloud-storable alarm model. The CLI accepts arbitrary alarm keys through `--set key=value` and can upload a complete JSON payload, so it is not limited to the small set of convenience flags.

The Android `Alarm` model currently contains controls for:

- time, label, enable state, repeat days
- ringtone URI, ringtone pool, Spotify URI, internet radio URL
- volume, system-volume override, gradual volume, vibration settings and vibration delay
- snooze duration, snooze limit, progressive snooze
- all supported dismiss challenges and mission chains
- TTS announcement and wake confirmation
- smart alarm and holiday skipping
- NFC / barcode / photo / Wi-Fi challenge values
- Philips Hue pre-wake
- sunrise/sunset-relative schedules and weather offset
- specific dates and rotating shift patterns
- fixed or local timezone policy
- flashlight/strobe and sunrise simulation
- morning routine
- hardware-button behavior
- early dismiss and hold-to-dismiss
- guardian escalation
- location-based dismissal
- per-alarm webhook / Hue-scene / broadcast dismissal actions
- firing-screen background image and blur
- sorting / profile metadata

The web structured editor exposes the same model without a raw JSON editor, and the cloud sanitizes the same core values on write.

## 10. Important: ringtone and photo resources

There are two different kinds of settings.

### Portable cloud values

These can travel between phones because they are ordinary data, for example:

- `internetRadioUrl`
- `spotifyUri`
- `volume`
- `challengeType`
- `repeatDays`
- `ttsEnabled`
- `morningRoutine`
- schedules and other scalar settings

### Device-local references

These are strings pointing at resources that physically exist on a particular Android device:

- `ringtoneUri`
- `ringtonePool`
- `photoMatchUri`
- `firingBackgroundImageUri`
- `nfcTagId` and `barcodeValue` references

For example, a `content://...` ringtone URI selected on Phone A normally does not point at the same file on Phone B. The terminal can store the URI in the cloud, but it cannot make the underlying local file appear on another phone.

So:

`terminal -> cloud -> Android` transfers the alarm configuration.

It does **not** automatically transfer a private/local media file referenced by a `content://` URI.

## 11. When does the alarm reach the phone?

Terminal creation is written to the cloud and then persisted to GitHub through the cloud service.

Android picks it up during its normal cloud synchronization flow. The current app schedules an immediate one-time sync when the app process starts, and boot-triggered startup also queues one. Opening the app also performs an immediate sync. A periodic WorkManager sync remains as the fallback.

There is currently no push channel that wakes the Android process instantly from a terminal command. The guaranteed model is:

`terminal create -> cloud/GitHub -> next Android sync -> Room -> Android scheduler`

## 12. Error 422

FastAPI returns HTTP 422 when request validation fails before the endpoint handler runs. The common authentication case is the password length rule: the cloud requires at least 8 characters.

The Android cloud account screen should surface the server's actual validation message rather than only showing a generic `HttpException` message.

If a 422 still appears after the app build containing the improved error handling, the displayed detail identifies the rejected field/request shape.

## 13. Raw API examples

Login:

```bash
curl -sS -X POST "https://alarmclockxtreme-cloud.onrender.com/api/auth/login" \
  -H "content-type: application/json" \
  -d '{"email":"YOUR_EMAIL","password":"YOUR_PASSWORD"}'
```

List:

```bash
curl -sS "https://alarmclockxtreme-cloud.onrender.com/api/alarms?since=1970-01-01T00:00:00Z" \
  -H "authorization: Bearer $ACX_TOKEN"
```

Create:

```bash
curl -sS -X PUT "https://alarmclockxtreme-cloud.onrender.com/api/alarms/NEW_UUID" \
  -H "authorization: Bearer $ACX_TOKEN" \
  -H "content-type: application/json" \
  -d '{
    "expectedVersion": 0,
    "payload": {
      "hour": 6,
      "minute": 30,
      "label": "JEE Study",
      "isEnabled": true,
      "repeatDays": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"]
    }
  }'
```

For normal use, prefer `scripts/alarmctl.py` because it handles authentication, IDs, versions, and error messages for you.

## 14. Recommended terminal workflow

After setting up the account once:

```bash
python3 scripts/alarmctl.py login
python3 scripts/alarmctl.py create --time 05:30 --label "Wake" --days MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY --challenge MATH_EASY
python3 scripts/alarmctl.py list
```

Then the same account is visible to the web dashboard and the Android app after cloud sync.

## Security notes

Do not commit `~/.config/alarmclockxtreme/token.json` or any exported JWT to GitHub.

For CI or shell automation, use:

`ACX_TOKEN`

rather than placing a token directly in command history.

The CLI never stores the account password.
