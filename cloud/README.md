# AlarmClockXtreme Cloud

This folder adds the web control plane for the existing native Android AlarmClockXtreme app.

## Architecture

- Android remains the native scheduling layer for device alarm behaviour.
- Cloud stores authenticated accounts and synchronized alarm payloads in PostgreSQL.
- The web dashboard can create, edit, enable or disable, delete, export, and inspect alarms.
- Android syncs on login, app resume, native Room changes, and a 15-minute WorkManager fallback.
- AI uses controlled alarm CRUD tools and is scoped to the authenticated user.

## Render

render.yaml is a Blueprint for a Node web service plus PostgreSQL.
The service binds to 0.0.0.0:$PORT and exposes /api/health.

Important: Render's current Free Postgres plan expires after 30 days. For long-lived data, use a persistent PostgreSQL provider or a paid database plan.

## Environment

Required: DATABASE_URL and JWT_SECRET.
Optional AI provider: AI_API_KEY, AI_BASE_URL, AI_MODEL.

Never put real passwords, JWT secrets, database URLs, API keys, or user datasets in this public repository.

## Android

The native client defaults to https://alarmclockxtreme-cloud.onrender.com/.
Override the endpoint during an Android build with -PcloudBaseUrl=https://your-service.onrender.com/.
In the app, open Settings and tap the cloud icon to register or log in and run AI commands.

## Device-only functions

Browser control manages cloud alarm configuration. Exact native scheduling, NFC/camera/step dismissal, ringtone playback, and OS or hardware integrations still execute on Android.