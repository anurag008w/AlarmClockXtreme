# AlarmClockXtreme Cloud

The existing native Android AlarmClockXtreme app now has a web control plane backed by the same native alarm model.

## GitHub data sync

This follows the SmartRotator pattern:
- private GitHub data repository
- per-user JSON files under an isolated alarmclockxtreme/ directory
- startup pull before the service accepts traffic
- automatic push after data mutations
- periodic pull when the local dataset is clean
- push protection when startup pull failed
- no PostgreSQL database

The default data repository is anurag008w/smartrotator-data in the alarmclockxtreme/ subdirectory.

A user's password is stored only as a salted scrypt hash. Never store a plaintext password, JWT secret, GitHub token, or AI API key in the data repository.

## Render

The root render.yaml deploys the cloud/ directory as a Python service.

Required Render secrets: GH_TOKEN and JWT_SECRET.
Optional AI secrets: AI_API_KEY, AI_BASE_URL, AI_MODEL.

The service intentionally refuses to start if GitHub sync is unavailable, because an empty ephemeral filesystem must never overwrite the existing private dataset.

## Android

The native Android app uses the same API for login, account registration, alarm sync, and AI commands.
The default endpoint is https://alarmclockxtreme-cloud.onrender.com/.
Override it at build time with -PcloudBaseUrl=https://your-service.onrender.com/.

Native exact scheduling, ringtone playback, dismissal challenges, NFC/camera/step integrations, and other OS-specific alarm behaviour remain on Android. Web changes sync alarm configuration and the phone applies native scheduling locally.

## Web

The web dashboard provides clock, cloud alarm CRUD, timer, stopwatch, world clocks, AI alarm control, activity, and export.


## Background push (FCM)

After an alarm, setting, utility command or AI command is written through the API, the server sends the user's phones a data-only Firebase Cloud Messaging wake-up. The message contains no alarm data: the app reacts by running its normal cloud sync, so a lost or forged push cannot change anything on its own.

- Set `FCM_SERVICE_ACCOUNT_JSON` (full service-account JSON for the Firebase project) in the Render environment. Without it push is skipped and phones keep syncing by polling.
- `GET /api/health` reports `"push": true` when the sender is configured.
- Phones send their token through `POST /api/devices/register` (`pushToken`). Tokens are never returned by the API; `GET /api/utilities/devices` only shows `push: true/false`.
- Only the Play build registers a token. The F-Droid build has no Google dependencies and keeps polling.
- Bursts of writes are coalesced into one push. Dead tokens (`UNREGISTERED`) are removed automatically.
