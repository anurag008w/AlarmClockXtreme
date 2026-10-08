# AlarmClockXtreme - Feature list

Status: as of v1.16.7 (released). Items under "Coming in 1.16.8" are planned, not shipped yet.

## Supported Android versions

- Minimum: **Android 8.0 (API 26)**. Built for Android 16 (API 36).
- Phones, tablets, foldables, Chromebooks and Samsung DeX are supported.
- Not every feature behaves the same on every Android version. The core alarm (exact ring, reboot restore, snooze, dismiss, challenges, timer, stopwatch, world clock) works on all of Android 8.0 and up. Things that depend on the version:
  - Notification permission prompt: Android 13+. On older versions notifications are on by default.
  - Material You (wallpaper) colours: Android 12+.
  - Per-app language picker in system settings: Android 13+ (the in-app language choice works everywhere).
  - Bedtime Do Not Disturb rule: Android 10+.
  - Full-screen alarm permission screen and some readiness checks: Android 14+.
  - Some diagnostics (why an alarm was late) need Android 9+, and a few extra details need Android 13/14.
  - Health Connect sleep reads (Play build): need the Health Connect app on Android 13 and below. It is built in from Android 14.
  - Wear OS controls need a paired watch and the separate Wear APK.
- Some phone makers (vivo, Xiaomi, Oppo, etc.) kill background apps. The Settings > Wake readiness screen tells you what to turn on (battery optimisation off, autostart). Without that, no alarm app can be fully reliable on those phones.
- Two builds: Play APK (everything below) and F-Droid APK (same alarm engine, without YouTube downloads, Health Connect, handwriting recognition, Wear bridge and crash reporting).
- This list is from the code. The app has not been run on every Android version or phone.

## Today (dashboard)

- Next alarm and quick actions.
- Weather for your location (automatic or manual city): temperature, feels like, humidity, wind, UV, air quality, sunrise and sunset.
- US weather alerts (National Weather Service), live radar card.
- Calendar events for the day, public holidays.
- Tiles can be switched on or off.

## Alarms

- Alarm list with time-of-day icons, on/off switch, label, repeat days, next ring time.
- Search, group filters, manual reorder (drag), multi-select, swipe to delete.
- Per alarm menu: edit, duplicate, share, skip next, history.
- Alarm editor: time, label, repeat days, one-time date alarms, sunrise/sunset offsets, rotating shift patterns (DDNNO, 4-on-4-off, Panama, DuPont, Pitman), profiles, groups, templates.
- Exact scheduling, restore after reboot, Direct Boot fallback for the next alarm, missed-alarm watchdog.
- Snooze with progressive snooze, auto-silence, vacation mode, public-holiday auto-skip, pause until a date.
- Wake confirmation: rings again if you fall back asleep. Smart wake (watches for light movement before the alarm). Guardian contact.
- 30 dismiss challenges: math (adaptive), typing, memory, walking, squats, push-ups, NFC, barcode, Wi-Fi, photo match, maze, Wordle, handwriting, voice phrase and more. Chain several challenges. Accessibility bypass after a chosen delay.
- Sounds: system ringtones, ringtone pools, internet radio (HTTPS), Spotify, gradual volume, vibration patterns, haptic-only alarms. Play build: search YouTube or paste a link, save the audio as an alarm sound.
- Automation when an alarm rings or is dismissed: webhooks, Tasker broadcasts, Philips Hue scenes.
- Share an alarm with a link, import a shared alarm.
- Morning briefing screen after dismissing.

## Timer

- Several timers at once, presets, a keypad, a progress ring, notifications.

## Stopwatch

- Start, Lap, Pause with a lap table.

## World clock

- Multiple cities, day/night tile for each, time difference, 12/24 hour.

## News

- Optional RSS feeds, with small pictures when the feed gives one.

## Bedtime and sleep

- Bedtime reminder, sleep goal, wind-down checklist, guided breathing, sleep sounds, night clock, jet-lag helper, chronotype estimate.
- Sonar sleep tracking (movement and loud-sound summaries, kept 30 days, no raw audio saved). Health Connect on the Play build.
- Stats screen: sleep and wake analytics.

## Cloud sync (optional)

- Sign in to sync alarms and settings with the web dashboard. Changes are picked up within seconds.
- Local setting changes always win over stale cloud data (fixed in 1.16.7).

## Updates

- Checks for a new version every time the app opens (if "Show update popup" is on).
- Popup shows the plain-language changelog, with download and install.
- Optional auto-download. Manual check in Settings > Updates.

## Settings

- Wake readiness check, permissions help, battery optimisation guidance.
- Look: navy theme, accent colour, optional Material You colours (Android 12+), time format, temperature unit, language.
- Backup and restore (encrypted backup), import from Fossify Clock.
- Integrations: webhooks, Tasker, Hue, Spotify, calendar, cloud account.
- Diagnostics and support bundle (crash logs stay on the phone unless you export them).
- Tabs can be hidden or shown (Today, Timer, World, News).

## Widgets, tiles and watch

- Home-screen next-alarm widget, Quick Settings tile to skip the next alarm.
- Wear OS: next-alarm tile, complication, skip, snooze and dismiss.

## Privacy

- No ads, no tracking, no account needed for the alarm engine.
- Crash reports (Play build): from the next release the Play build sends app crash reports to Firebase Crashlytics so crashes can be fixed. The F-Droid build never does.

## Coming in 1.16.8 (planned)

- Alarm edit grouped rows, Dashboard 4-tile strip and weather details, air quality gauge, alarm sound thumbnails, more alarm list polish.
