const SETTINGS_TYPES = {"worldClockZones":"String","is24HourFormat": "Boolean", "defaultSnoozeDuration": "Int", "defaultGradualVolume": "Int", "usePhoneSpeakers": "Boolean", "showAlarmClockIcon": "Boolean", "hideAlarmLabelsOnPublicSurfaces": "Boolean", "vacationModeEnabled": "Boolean", "vacationStartMillis": "Long", "vacationEndMillis": "Long", "showWeatherOnDashboard": "Boolean", "showCalendarOnDashboard": "Boolean", "postDismissSummaryEnabled": "Boolean", "autoSilenceMinutes": "Int", "temperatureUnit": "String", "locationName": "String", "useManualLocation": "Boolean", "bedtimeEnabled": "Boolean", "bedtimeHour": "Int", "bedtimeMinute": "Int", "sleepGoalHours": "Int", "sleepGoalMinutes": "Int", "bedtimeReminderMinutes": "Int", "bedtimeStayUpLateUntilMillis": "Long", "flipToSnoozeEnabled": "Boolean", "webhookEnabled": "Boolean", "webhookUrl": "String", "webhookIncludeLabel": "Boolean", "holidayAutoSkipEnabled": "Boolean", "holidayCountryCode": "String", "accentColor": "String", "adaptiveDifficultyEnabled": "Boolean", "customTypingPhrases": "String", "showMotivationalQuotes": "Boolean", "dynamicColorEnabled": "Boolean", "expressiveModeEnabled": "Boolean", "reduceMotionAndFlashing": "Boolean", "coverToSnoozeEnabled": "Boolean", "bedtimeChecklist": "String", "sleepSoundTimerMinutes": "Int", "sleepSoundFadeSeconds": "Int", "repeatMissedAlarms": "Boolean", "napDefaultMinutes": "Int", "showDashboardTab": "Boolean", "showTimerTab": "Boolean", "showWorldClockTab": "Boolean", "showNewsTab": "Boolean", "showRadarEmbed": "Boolean", "newsFeedUrl": "String", "pauseUntilMillis": "Long", "cancellationLockMinutes": "Int", "holdToDismissMillis": "Int", "firingControlMode": "String", "challengeBypassEnabled": "Boolean", "challengeBypassDelaySeconds": "Int", "challengeAudioDuckingEnabled": "Boolean", "challengeAudioDuckPercent": "Int"};
const SETTINGS_DEFAULTS = {"worldClockZones":"America/New_York|America/Los_Angeles|Europe/London|Asia/Tokyo","is24HourFormat": false, "defaultSnoozeDuration": 10, "defaultGradualVolume": 60, "usePhoneSpeakers": false, "showAlarmClockIcon": true, "hideAlarmLabelsOnPublicSurfaces": false, "vacationModeEnabled": false, "vacationStartMillis": 0, "vacationEndMillis": 0, "showWeatherOnDashboard": true, "showCalendarOnDashboard": true, "postDismissSummaryEnabled": false, "autoSilenceMinutes": 10, "temperatureUnit": "fahrenheit", "locationName": "", "useManualLocation": false, "bedtimeEnabled": false, "bedtimeHour": 23, "bedtimeMinute": 0, "sleepGoalHours": 8, "sleepGoalMinutes": 0, "bedtimeReminderMinutes": 30, "bedtimeStayUpLateUntilMillis": 0, "flipToSnoozeEnabled": false, "webhookEnabled": false, "webhookUrl": "", "webhookIncludeLabel": true, "holidayAutoSkipEnabled": false, "holidayCountryCode": "", "accentColor": "#5B9EF4", "adaptiveDifficultyEnabled": false, "customTypingPhrases": "", "showMotivationalQuotes": true, "dynamicColorEnabled": false, "expressiveModeEnabled": true, "reduceMotionAndFlashing": false, "coverToSnoozeEnabled": false, "bedtimeChecklist": "", "sleepSoundTimerMinutes": 0, "sleepSoundFadeSeconds": 60, "repeatMissedAlarms": true, "napDefaultMinutes": 20, "showDashboardTab": true, "showTimerTab": true, "showWorldClockTab": true, "showNewsTab": true, "showRadarEmbed": true, "newsFeedUrl": "https://feeds.bbci.co.uk/news/rss.xml", "pauseUntilMillis": 0, "cancellationLockMinutes": 0, "holdToDismissMillis": 1500, "firingControlMode": "hybrid", "challengeBypassEnabled": false, "challengeBypassDelaySeconds": 30, "challengeAudioDuckingEnabled": false, "challengeAudioDuckPercent": 35};
const SETTINGS_GROUPS = {"Bedtime & sleep": ["bedtimeEnabled", "bedtimeHour", "bedtimeMinute", "sleepGoalHours", "sleepGoalMinutes", "bedtimeReminderMinutes", "bedtimeStayUpLateUntilMillis", "bedtimeChecklist", "sleepSoundTimerMinutes", "sleepSoundFadeSeconds"], "Challenges & dismissal": ["flipToSnoozeEnabled", "adaptiveDifficultyEnabled", "customTypingPhrases", "coverToSnoozeEnabled", "cancellationLockMinutes", "holdToDismissMillis", "firingControlMode", "challengeBypassEnabled", "challengeBypassDelaySeconds", "challengeAudioDuckingEnabled", "challengeAudioDuckPercent"], "Schedules & defaults": ["defaultSnoozeDuration", "defaultGradualVolume", "vacationModeEnabled", "vacationStartMillis", "vacationEndMillis", "autoSilenceMinutes", "holidayAutoSkipEnabled", "holidayCountryCode", "repeatMissedAlarms", "napDefaultMinutes", "pauseUntilMillis"], "Sound & privacy": ["usePhoneSpeakers", "showAlarmClockIcon", "hideAlarmLabelsOnPublicSurfaces", "postDismissSummaryEnabled"], "Integrations": ["webhookEnabled", "webhookUrl", "webhookIncludeLabel", "newsFeedUrl"], "World clocks": ["worldClockZones"], "Appearance & Today": ["is24HourFormat", "showWeatherOnDashboard", "showCalendarOnDashboard", "temperatureUnit", "locationName", "useManualLocation", "accentColor", "showMotivationalQuotes", "dynamicColorEnabled", "expressiveModeEnabled", "reduceMotionAndFlashing", "showDashboardTab", "showTimerTab", "showWorldClockTab", "showNewsTab", "showRadarEmbed"]};
const SETTINGS_LABELS = {"worldClockZones":"World clocks (IANA zone IDs separated by |, empty removes all)","is24HourFormat": "Is24 hour format", "defaultSnoozeDuration": "Default snooze duration", "defaultGradualVolume": "Default gradual volume", "usePhoneSpeakers": "Use phone speakers", "showAlarmClockIcon": "Show alarm clock icon", "hideAlarmLabelsOnPublicSurfaces": "Hide alarm labels on public surfaces", "vacationModeEnabled": "Vacation mode enabled", "vacationStartMillis": "Vacation start (epoch milliseconds)", "vacationEndMillis": "Vacation end (epoch milliseconds)", "showWeatherOnDashboard": "Show weather on dashboard", "showCalendarOnDashboard": "Show calendar on dashboard", "postDismissSummaryEnabled": "Post dismiss summary enabled", "autoSilenceMinutes": "Auto silence minutes", "temperatureUnit": "Temperature unit", "locationName": "Location name", "useManualLocation": "Use manually selected phone location", "bedtimeEnabled": "Bedtime enabled", "bedtimeHour": "Bedtime hour", "bedtimeMinute": "Bedtime minute", "sleepGoalHours": "Sleep goal hours", "sleepGoalMinutes": "Sleep goal minutes", "bedtimeReminderMinutes": "Bedtime reminder minutes", "bedtimeStayUpLateUntilMillis": "Bedtime stay up late until millis", "flipToSnoozeEnabled": "Flip to snooze enabled", "webhookEnabled": "Webhook enabled", "webhookUrl": "Webhook url", "webhookIncludeLabel": "Webhook include label", "holidayAutoSkipEnabled": "Holiday auto skip enabled", "holidayCountryCode": "Holiday country code", "accentColor": "Accent color", "adaptiveDifficultyEnabled": "Adaptive difficulty enabled", "customTypingPhrases": "Typing phrases (one per line)", "showMotivationalQuotes": "Show motivational quotes", "dynamicColorEnabled": "Dynamic color enabled", "expressiveModeEnabled": "Expressive mode enabled", "reduceMotionAndFlashing": "Reduce motion and flashing", "coverToSnoozeEnabled": "Cover to snooze enabled", "bedtimeChecklist": "Bedtime checklist (one item per line)", "sleepSoundTimerMinutes": "Sleep sound timer minutes", "sleepSoundFadeSeconds": "Sleep sound fade seconds", "repeatMissedAlarms": "Repeat missed alarms", "napDefaultMinutes": "Nap default minutes", "showDashboardTab": "Show dashboard tab", "showTimerTab": "Show timer tab", "showWorldClockTab": "Show world clock tab", "showNewsTab": "Show news tab", "showRadarEmbed": "Show radar embed", "newsFeedUrl": "News feed url", "pauseUntilMillis": "Pause alarms until (epoch milliseconds)", "cancellationLockMinutes": "Cancellation lock minutes", "holdToDismissMillis": "Hold to dismiss millis", "firingControlMode": "Firing control mode", "challengeBypassEnabled": "Challenge bypass enabled", "challengeBypassDelaySeconds": "Challenge bypass delay seconds", "challengeAudioDuckingEnabled": "Challenge audio ducking enabled", "challengeAudioDuckPercent": "Challenge audio duck percent"};
let phoneSettings = {...SETTINGS_DEFAULTS};
let phoneSettingsVersion = 0;
let phoneSettingsDirty = false;
let worldZonesSynced = false;

function renderSettings() {
  $("settingsEditor").innerHTML = `<div id="worldClockReadout" class="card"></div>` + Object.entries(SETTINGS_GROUPS).map(([name,keys],index) => `<details class="card settings-group" ${index===0 ? "open" : ""}><summary>${escapeHtml(name)}</summary><div class="form-grid">${keys.map(key => {
    if (key === "worldClockZones" && !worldZonesSynced) return "<p>Sync an updated Android app before editing saved zones.</p>";
    const value = phoneSettings[key];
    if (SETTINGS_TYPES[key] === "Boolean") return fieldSwitch(key,SETTINGS_LABELS[key],Boolean(value));
    if (["customTypingPhrases","bedtimeChecklist"].includes(key)) return fieldTextarea(key,SETTINGS_LABELS[key],value,"",true);
    if (key === "temperatureUnit") return fieldSelect(key,SETTINGS_LABELS[key],value,[["celsius","Celsius"],["fahrenheit","Fahrenheit"]]);
    if (key === "firingControlMode") return fieldSelect(key,SETTINGS_LABELS[key],value,[["hybrid","Hybrid"],["buttons","Buttons"],["swipe","Swipe"]]);
    if (SETTINGS_TYPES[key] === "String") return fieldText(key,SETTINGS_LABELS[key],value,"",true);
    return fieldNumber(key,SETTINGS_LABELS[key],value,0,Number.MAX_SAFE_INTEGER,1);
  }).join("")}</div></details>`).join("");
  renderWorldClocks();
  $("settingsEditor").querySelectorAll("details").forEach(group => group.addEventListener("toggle", () => {
    if (group.open) $("settingsEditor").querySelectorAll("details").forEach(other => { if(other!==group) other.open=false; });
  }));
  $("settingsEditor").querySelectorAll("[data-field]").forEach(input => input.addEventListener("change", () => {
    const key=input.dataset.field;
    if (key === "worldClockZones" && !worldZonesSynced) { $("settingsStatus").textContent="Sync an updated phone first to preserve existing zones."; return; }
    phoneSettings[key]=SETTINGS_TYPES[key]==="Boolean" ? input.checked : SETTINGS_TYPES[key]==="String" ? input.value : Number(input.value);
    phoneSettingsDirty=true;
    $("settingsStatus").textContent="Unsaved settings. Existing phone configuration is unchanged.";
  }));
}
async function loadSettings() {
  const data = await api("/api/settings");
  worldZonesSynced = Object.hasOwn(data.payload, "worldClockZones");
  phoneSettings={...SETTINGS_DEFAULTS,...data.payload};
  state.worldZones = worldZonesSynced && data.payload.worldClockZones ? data.payload.worldClockZones.split("|") : [];
  renderWorld();
  phoneSettingsVersion=data.version;
  phoneSettingsDirty=false;
  renderSettings();
  $("settingsStatus").textContent=data.version ? `Cloud version ${data.version}. Phone applies this on sync.` : "No cloud settings yet. Sync an updated Android app before editing, or review all defaults first.";
}
async function savePhoneSettings() {
  if (!phoneSettingsDirty) return;
  if (!phoneSettingsVersion) { $("settingsStatus").textContent="Sync an updated Android app first so existing settings are not replaced by guessed defaults."; return; }
  const button=$("saveSettingsBtn");button.disabled=true;
  try {
    const saved=await api("/api/settings",{method:"PUT",body:JSON.stringify({payload:Object.fromEntries(Object.entries(phoneSettings).filter(([key]) => key!=="worldClockZones" || worldZonesSynced)),expectedVersion:phoneSettingsVersion})});
    const readback=await api("/api/settings");
    if(readback.version!==saved.version || JSON.stringify(readback.payload)!==JSON.stringify(saved.payload)) throw new Error("settings_changed_after_save_reload_before_retry");
    phoneSettingsVersion=saved.version;phoneSettingsDirty=false;
    if (worldZonesSynced) state.worldZones = phoneSettings.worldClockZones ? phoneSettings.worldClockZones.split("|") : [];
    renderWorld();renderWorldClocks();
    $("settingsStatus").textContent=`Cloud version ${saved.version} saved and checked. Awaiting Android sync, not proof of phone delivery.`;
  } catch(error) { $("settingsStatus").textContent=error.message==="version_conflict" ? "Another device edited settings. Reload before saving; your unsaved form has been kept." : error.message.replaceAll("_"," "); }
  finally {button.disabled=false;}
}

function renderWorldClocks() {
  const node = document.getElementById("worldClockReadout");
  if (!node) return;
  if (!phoneSettingsVersion || !worldZonesSynced) {
    node.textContent = "World clocks: waiting for an updated phone to sync its zones."; return;
  }
  const zones = phoneSettings.worldClockZones ? phoneSettings.worldClockZones.split("|") : [];
  node.innerHTML = `<h3>World clocks</h3>${zones.length ? zones.map(zone => {
    try { const time = new Intl.DateTimeFormat(undefined,{timeZone:zone,dateStyle:"medium",timeStyle:"short",hour12:!phoneSettings.is24HourFormat}).format(new Date()); return `<p>${escapeHtml(zone)}<br><strong>${escapeHtml(time)}</strong></p>`; }
    catch (_) {return `<p>${escapeHtml(zone)}: this browser does not support this zone</p>`;}
  }).join("") : "No saved zones"}`;
}
setInterval(() => { if (!document.hidden) renderWorldClocks(); }, 30000);

async function saveWorldZones(zones) {
  if (!worldZonesSynced || !phoneSettingsVersion) { alert("Sync an updated phone first to preserve your saved world clocks."); return; }
  if (phoneSettingsDirty) { alert("Save or reload unsaved settings before editing world clocks."); return; }
  const before = phoneSettings.worldClockZones;
  phoneSettings.worldClockZones=zones.join("|");phoneSettingsDirty=true;
  await savePhoneSettings();
  if (phoneSettingsDirty) {phoneSettings.worldClockZones=before;phoneSettingsDirty=false;renderWorld();}
}
