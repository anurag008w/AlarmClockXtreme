const state = {
  token: localStorage.getItem("acx_token") || "",
  user: null,
  alarms: [],
  editing: null,
  loginMode: true,
  worldZones: ["Asia/Kolkata", "Europe/London", "America/New_York"]
};

let syncPromise = null;
let syncController = null;
let syncTimer = null;

const $ = (id) => document.getElementById(id);

const CHALLENGES = [
  ["NONE","None"],["MATH_EASY","Math · Easy"],["MATH_MEDIUM","Math · Medium"],["MATH_HARD","Math · Hard"],
  ["SHAKE","Shake"],["SEQUENCE","Sequence"],["MEMORY_PATTERN","Memory Pattern"],["TYPING","Typing"],
  ["VOICE_PHRASE","Voice Phrase"],["HANDWRITING","Handwriting"],["WALK_STEPS","Walk Steps"],["NFC_SCAN","NFC Scan"],
  ["BARCODE_SCAN","Barcode / QR"],["PHOTO_MATCH","Photo Match"],["SQUAT","Squats"],["WIFI_CONNECT","Wi-Fi Connect"],
  ["MAZE","Maze"],["COUNT_SHEEP","Count Sheep"],["SIMON_SAYS","Simon Says"],["DATE_BACKWARDS","Date Backwards"],
  ["STROOP","Stroop"],["ROCK_PAPER_SCISSORS","Rock Paper Scissors"],["EMOJI_MEMORY","Emoji Memory"],
  ["TYPING_SPEED","Typing Speed"],["WORDLE","Wordle"],["PVT","PVT"],["SPOT_DIFFERENCE","Spot Difference"],
  ["CHESS_MATE","Chess Mate"],["RSVP_READING","RSVP Reading"],["PUSH_UP","Push-up"],["PLANK_HOLD","Plank Hold"]
];

const DAYS = [
  ["MONDAY","M"],["TUESDAY","T"],["WEDNESDAY","W"],["THURSDAY","T"],
  ["FRIDAY","F"],["SATURDAY","S"],["SUNDAY","S"]
];

const EDITOR_TABS = [
  ["overview","Overview"],["sound","Sound"],["dismiss","Dismiss"],["schedule","Schedule"],
  ["wake","Wake"],["integrations","Integrations"],["advanced","Advanced"]
];

const DEFAULT_ALARM = {
  id: 0, hour: 7, minute: 0, label: "", isEnabled: true, repeatDays: [],
  ringtoneUri: "", vibrationEnabled: true, vibrationIntensity: 2, volume: 100,
  overrideSystemVolume: true, gradualVolumeSeconds: 60, snoozeDurationMinutes: 10,
  maxSnoozeCount: 3, showOnLockScreen: true, challengeType: "NONE", group: "",
  flashWake: false, vibrationPattern: "default", createdAt: Date.now(), nextTriggerTime: 0,
  ttsEnabled: false, walkStepsRequired: 30, wakeConfirmEnabled: false,
  wakeConfirmDelayMinutes: 10, smartAlarmEnabled: false, smartAlarmWindowMinutes: 30,
  skipOnHolidays: false, nfcTagId: "", barcodeValue: "", spotifyUri: "",
  hueEnabled: false, huePreWakeMinutes: 30, photoMatchUri: "", challengeChain: "",
  progressiveSnooze: false, backupSoundEnabled: false, backupSoundDelaySec: 40,
  sunriseSimulation: false, sunriseMinutes: 15, specificDate: "", profileName: "",
  earlyDismissMinutes: 0, guardianEnabled: false, guardianPhone: "", guardianDelaySec: 300,
  locationDismissEnabled: false, locationDismissLat: 0, locationDismissLng: 0,
  locationDismissRadius: 100, wifiDismissSsid: "", internetRadioUrl: "",
  flashlightStrobe: false, morningRoutine: "", hardwareButtonAction: "NONE",
  dismissAtRingtoneEnd: false, holdToDismissEnabled: false, ringtonePool: "",
  solarOffsetMinutes: 0, solarAnchor: "SUNRISE", vibrationDelaySeconds: 0,
  weatherEarlyMinutes: 0, requiredSquats: 10, dismissActionType: "NONE",
  dismissActionPayload: "", firingBackgroundImageEnabled: false, firingBackgroundImageUri: "",
  firingBackgroundBlurEnabled: true, sortOrder: 0, shiftPattern: "", shiftPatternStartDate: "",
  timezonePolicy: "LOCAL", fixedTimezoneId: ""
};

const num = (value, fallback) => Number.isFinite(Number(value)) ? Number(value) : fallback;
const bool = (value, fallback = false) => typeof value === "boolean" ? value : String(value).toLowerCase() === "true" ? true : String(value).toLowerCase() === "false" ? false : fallback;
const clone = (value) => JSON.parse(JSON.stringify(value));
const options = (items, selected) => items.map(([value,label]) => `<option value="${escapeAttr(value)}" ${value === selected ? "selected" : ""}>${escapeHtml(label)}</option>`).join("");

function setAuthError(message = "") { $("authError").textContent = message; }
function headers(extra = {}) {
  return { "content-type": "application/json", ...extra, ...(state.token ? { authorization: `Bearer ${state.token}` } : {}) };
}
async function api(path, optionsArg = {}) {
  const response = await fetch(path, { ...optionsArg, headers: { ...headers(), ...(optionsArg.headers || {}) } });
  const body = await response.json().catch(() => ({}));
  if (response.status === 401) {
    state.token = "";
    localStorage.removeItem("acx_token");
    showAuth();
  }
  if (!response.ok) throw new Error(body.error || body.detail || `request_failed_${response.status}`);
  return body;
}

function showAuth() {
  $("authView").classList.remove("hidden");
  $("appView").classList.add("hidden");
}
function showApp() {
  $("authView").classList.add("hidden");
  $("appView").classList.remove("hidden");
  $("hello").textContent = state.user?.email || "cloud";
}

function cursorStorageKey() {
  return state.user?.id ? `acx_cursor_${state.user.id}` : "acx_cursor_anon";
}

async function refreshCloudDataset() {
  return api("/api/sync/refresh", { method: "POST" });
}

async function authSubmit(event) {
  event.preventDefault();
  setAuthError("");
  try {
    const path = state.loginMode ? "/api/auth/login" : "/api/auth/register";
    const data = await api(path, {
      method: "POST",
      body: JSON.stringify({ email: $("email").value, password: $("password").value })
    });
    state.token = data.token;
    state.user = data.user;
    state.alarms = [];
    localStorage.setItem("acx_token", state.token);
    localStorage.removeItem(cursorStorageKey());
    await refreshCloudDataset();
    showApp();
    await syncNow({ forceFull: true });
    renderWorld();
    refreshActivity();
  } catch (error) {
    setAuthError(error.message.replaceAll("_", " "));
  }
}

function switchAuth(mode) {
  state.loginMode = mode === "login";
  $("loginTab").classList.toggle("active", state.loginMode);
  $("registerTab").classList.toggle("active", !state.loginMode);
  $("authAction").textContent = state.loginMode ? "login" : "create account";
  $("password").autocomplete = state.loginMode ? "current-password" : "new-password";
  setAuthError("");
}

async function syncNow({ forceFull = false, silent = false } = {}) {
  if (syncPromise) return syncPromise;
  syncPromise = (async () => {
    if (!silent) $("syncState").textContent = "syncing…";
    syncController = new AbortController();
    const timeout = setTimeout(() => syncController?.abort(), 12000);
    try {
      if (forceFull) state.alarms = [];
      const cursorKey = cursorStorageKey();
      const cursor = forceFull ? new Date(0).toISOString() :
        (localStorage.getItem(cursorKey) || new Date(0).toISOString());
      const data = await api(`/api/alarms?since=${encodeURIComponent(cursor)}`, { signal: syncController.signal });
      for (const remote of data.alarms) {
        const existing = state.alarms.findIndex(a => a.id === remote.id);
        if (remote.deletedAt) {
          if (existing >= 0) state.alarms.splice(existing, 1);
        } else if (existing >= 0) {
          state.alarms[existing] = remote;
        } else {
          state.alarms.push(remote);
        }
      }
      localStorage.setItem(cursorKey, data.cursor);
      state.alarms.sort((a,b) => {
        const ah = Number(a.payload?.hour ?? 0), bh = Number(b.payload?.hour ?? 0);
        const am = Number(a.payload?.minute ?? 0), bm = Number(b.payload?.minute ?? 0);
        return (ah * 60 + am) - (bh * 60 + bm);
      });
      if (!silent) $("syncState").textContent = "synced";
      renderAlarms();
      return data;
    } catch (error) {
      if (!silent) {
        $("syncState").textContent = error.name === "AbortError" ? "sync timeout" : "sync error";
      }
      if (error.message === "missing_token" || error.message === "invalid_token") showAuth();
      throw error;
    } finally {
      clearTimeout(timeout);
      syncController = null;
      syncPromise = null;
    }
  })();
  return syncPromise;
}

async function refreshAfterConflict() {
  state.editing = null;
  try {
    await syncNow({ forceFull: true, silent: true });
    $("syncState").textContent = "conflict refreshed";
  } catch {}
}

function escapeHtml(value) {
  return String(value ?? "").replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;").replaceAll("'","&#39;");
}
function escapeAttr(value) { return escapeHtml(value); }

function alarmSummary(payload) {
  const days = Array.isArray(payload.repeatDays) && payload.repeatDays.length
    ? payload.repeatDays.map(x => String(x).slice(0,3)).join(" · ") : "once";
  const challenge = payload.challengeType && payload.challengeType !== "NONE"
    ? payload.challengeType.replaceAll("_"," ") : "no challenge";
  const extras = [];
  if (payload.smartAlarmEnabled) extras.push("smart");
  if (payload.hueEnabled) extras.push("hue");
  if (payload.ttsEnabled) extras.push("tts");
  if (payload.timezonePolicy === "FIXED" && payload.fixedTimezoneId) extras.push(payload.fixedTimezoneId);
  return `${days} · ${challenge}${extras.length ? " · " + extras.join(" · ") : ""}`;
}

function nextFireLabel(payload) {
  const now = new Date();
  const hour = num(payload.hour, 7);
  const minute = num(payload.minute, 0);
  const specific = String(payload.specificDate || "");
  if (specific) {
    const target = new Date(`${specific}T${String(hour).padStart(2,"0")}:${String(minute).padStart(2,"0")}:00`);
    if (target >= now) return target;
  }
  const repeat = new Set(Array.isArray(payload.repeatDays) ? payload.repeatDays : []);
  for (let offset = 0; offset <= 7; offset++) {
    const target = new Date(now);
    target.setDate(now.getDate() + offset);
    target.setHours(hour, minute, 0, 0);
    const day = ["SUNDAY","MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY"][target.getDay()];
    if (offset === 0 && target < now) continue;
    if (repeat.size && !repeat.has(day)) continue;
    return target;
  }
  return null;
}

function renderAlarms() {
  const box = $("alarmList");
  if (!state.alarms.length) {
    box.innerHTML = '<div class="card"><h2>no alarms yet</h2><p class="muted">create one here and it will appear in the Android app after sync</p></div>';
    return;
  }
  box.innerHTML = state.alarms.map(remote => {
    const p = remote.payload || {};
    const time = `${String(p.hour ?? 0).padStart(2,"0")}:${String(p.minute ?? 0).padStart(2,"0")}`;
    const next = nextFireLabel(p);
    const nextText = next ? `next ${next.toLocaleString([], {weekday:"short", month:"short", day:"numeric", hour:"2-digit", minute:"2-digit"})}` : "schedule unavailable";
    return `<article class="card alarm">
      <div class="alarm-time">${escapeHtml(time)}</div>
      <div class="alarm-label">${escapeHtml(p.label || "untitled alarm")}</div>
      <div class="alarm-meta">${escapeHtml(alarmSummary(p))}<br>${escapeHtml(nextText)} · volume ${escapeHtml(p.volume ?? 100)}</div>
      <span class="alarm-state ${p.isEnabled ? "enabled" : "disabled"}">${p.isEnabled ? "enabled" : "disabled"}</span>
      <div class="alarm-actions">
        <button class="secondary" data-edit="${escapeAttr(remote.id)}" type="button">edit</button>
        <button class="ghost" data-toggle="${escapeAttr(remote.id)}" type="button">${p.isEnabled ? "disable" : "enable"}</button>
      </div>
    </article>`;
  }).join("");

  box.querySelectorAll("[data-edit]").forEach(btn =>
    btn.addEventListener("click", () => openAlarm(state.alarms.find(x => x.id === btn.dataset.edit)))
  );
  box.querySelectorAll("[data-toggle]").forEach(btn =>
    btn.addEventListener("click", async () => {
      const remote = state.alarms.find(x => x.id === btn.dataset.toggle);
      if (!remote) return;
      try {
        const latest = await api(`/api/alarms/${remote.id}`, {
          method: "PUT",
          body: JSON.stringify({
            payload: { ...remote.payload, isEnabled: !remote.payload.isEnabled },
            expectedVersion: remote.version
          })
        });
        const idx = state.alarms.findIndex(x => x.id === latest.id);
        if (idx >= 0) state.alarms[idx] = latest;
        renderAlarms();
        refreshActivity();
      } catch (error) {
        if (error.message === "version_conflict" || error.message === "alarm_deleted_conflict") {
          await refreshAfterConflict();
          alert("This alarm changed on another device. The latest version was loaded and your stale toggle was not applied.");
          return;
        }
        alert(error.message.replaceAll("_", " "));
      }
    })
  );
}

function fieldText(key, label, value, help = "", wide = false, placeholder = "") {
  return `<label class="field ${wide ? "wide" : ""}">
    <span>${escapeHtml(label)}</span>
    <input data-field="${escapeAttr(key)}" type="text" value="${escapeAttr(value ?? "")}" placeholder="${escapeAttr(placeholder)}">
    ${help ? `<small class="field-help">${escapeHtml(help)}</small>` : ""}
  </label>`;
}
function fieldNumber(key, label, value, min, max, step=1, help="", wide=false) {
  return `<label class="field ${wide ? "wide" : ""}">
    <span>${escapeHtml(label)}</span>
    <input data-field="${escapeAttr(key)}" type="number" value="${escapeAttr(value)}" min="${min}" max="${max}" step="${step}">
    ${help ? `<small class="field-help">${escapeHtml(help)}</small>` : ""}
  </label>`;
}
function fieldSelect(key, label, value, items, help="", wide=false) {
  return `<label class="field ${wide ? "wide" : ""}">
    <span>${escapeHtml(label)}</span>
    <select data-field="${escapeAttr(key)}">${options(items, value)}</select>
    ${help ? `<small class="field-help">${escapeHtml(help)}</small>` : ""}
  </label>`;
}
function fieldTextarea(key, label, value, help="", wide=true) {
  return `<label class="field ${wide ? "wide" : ""}">
    <span>${escapeHtml(label)}</span>
    <textarea data-field="${escapeAttr(key)}" rows="3">${escapeHtml(value ?? "")}</textarea>
    ${help ? `<small class="field-help">${escapeHtml(help)}</small>` : ""}
  </label>`;
}
function ringtoneField(value) {
  const current = String(value ?? "");
  const options = [
    ["", "Default Alarm (Android device)"],
    ["silent", "Silent"]
  ];
  if (current && current !== "silent") {
    options.push([current, "Current Android ringtone (device-local)"]);
  }
  const selected = current;
  return `
    <label class="field wide">
      <span>Alarm ringtone</span>
      <select data-ringtone-preset>
        ${options.map(([v, label]) =>
          `<option value="${escapeAttr(v)}" ${v === selected ? "selected" : ""}>${escapeHtml(label)}</option>`
        ).join("")}
      </select>
      <small class="field-help">Default Alarm uses the phone's current Android system alarm tone. System ringtone files themselves are device-local.</small>
    </label>
    <label class="field wide">
      <span>Android ringtone URI</span>
      <input data-field="ringtoneUri" type="text" value="${escapeAttr(current)}" placeholder="content://…">
      <small class="field-help">Advanced override. The URI must be readable by the Android device receiving this alarm.</small>
    </label>`;
}

function fieldSwitch(key, label, checked, help="") {
  return `<label class="switch-field">
    <span class="switch-copy"><strong>${escapeHtml(label)}</strong>${help ? `<small>${escapeHtml(help)}</small>` : ""}</span>
    <input class="switch" data-field="${escapeAttr(key)}" type="checkbox" ${checked ? "checked" : ""}>
  </label>`;
}
function sectionCard(title, description, inner) {
  return `<section class="section-card">
    <div class="section-title"><div><h3>${escapeHtml(title)}</h3><div class="section-description">${escapeHtml(description)}</div></div></div>
    <div class="field-grid">${inner}</div>
  </section>`;
}

function repeatDaysEditor() {
  const selected = new Set(Array.isArray(editorDraft.repeatDays) ? editorDraft.repeatDays : []);
  return `<section class="section-card">
    <div class="section-title"><div><h3>repeat</h3><div class="section-description">Select the weekdays that should fire this alarm.</div></div></div>
    <div class="day-grid">${DAYS.map(([value,label]) => `<button type="button" class="day-button ${selected.has(value) ? "active" : ""}" data-day="${value}">${label}<span style="display:block;margin-top:3px;font-size:10px">${value.slice(0,3)}</span></button>`).join("")}</div>
    <div class="hint" style="margin-top:10px">${selected.size ? selected.size === 7 ? "Every day" : selected.size === 5 && ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"].every(x=>selected.has(x)) ? "Weekdays" : Array.from(selected).map(x=>x.slice(0,3)).join(" · ") : "Once"}</div>
  </section>`;
}

let editorDraft = clone(DEFAULT_ALARM);
let editorTab = "overview";

function openAlarm(remote = null) {
  state.editing = remote;
  editorDraft = { ...clone(DEFAULT_ALARM), ...(remote?.payload ? clone(remote.payload) : {}) };
  editorDraft.repeatDays = Array.isArray(editorDraft.repeatDays) ? [...editorDraft.repeatDays] : [];
  editorTab = "overview";
  $("dialogTitle").textContent = remote ? "edit alarm" : "new alarm";
  $("dialogSubtitle").textContent = remote ? `version ${remote.version} · cloud alarm` : "Create a full Android-compatible alarm";
  $("deleteAlarmBtn").classList.toggle("hidden", !remote);
  $("alarmDialog").showModal();
  renderEditor();
}

function renderPreview() {
  const next = nextFireLabel(editorDraft);
  const chips = [
    editorDraft.isEnabled ? "enabled" : "disabled",
    editorDraft.repeatDays?.length ? `${editorDraft.repeatDays.length} repeat days` : "once",
    editorDraft.challengeType !== "NONE" ? editorDraft.challengeType.replaceAll("_"," ") : "no challenge",
    `volume ${editorDraft.volume}`
  ];
  if (editorDraft.smartAlarmEnabled) chips.push("smart alarm");
  if (editorDraft.timezonePolicy === "FIXED" && editorDraft.fixedTimezoneId) chips.push(editorDraft.fixedTimezoneId);
  $("alarmPreview").innerHTML = `
    <div class="preview-time">${String(editorDraft.hour).padStart(2,"0")}:${String(editorDraft.minute).padStart(2,"0")}</div>
    <div class="preview-label">${escapeHtml(editorDraft.label || "untitled alarm")}</div>
    <div class="preview-meta">${escapeHtml(next ? `Next: ${next.toLocaleString([], {weekday:"short", month:"short", day:"numeric", hour:"2-digit", minute:"2-digit"})}` : "Next firing is determined by Android.")}</div>
    <div class="preview-chips">${chips.map(x=>`<span class="preview-chip">${escapeHtml(x)}</span>`).join("")}</div>`;
}

function challengeFields() {
  const c = editorDraft.challengeType;
  const fields = [];
  if (c === "WALK_STEPS") fields.push(fieldNumber("walkStepsRequired","Steps required",editorDraft.walkStepsRequired,1,10000,1,"Android counts the required steps before dismissal."));
  if (c === "SQUAT") fields.push(fieldNumber("requiredSquats","Squats required",editorDraft.requiredSquats,1,100,1));
  if (c === "NFC_SCAN") fields.push(fieldText("nfcTagId","NFC tag ID",editorDraft.nfcTagId,"The Android app matches this tag on the device.","wide"));
  if (c === "BARCODE_SCAN") fields.push(fieldText("barcodeValue","Barcode / QR value",editorDraft.barcodeValue,"Stored value used by the Android scanner.","wide"));
  if (c === "PHOTO_MATCH") fields.push(fieldText("photoMatchUri","Reference photo URI",editorDraft.photoMatchUri,"Android local photo reference; web cannot capture the reference photo.","wide"));
  if (c === "WIFI_CONNECT") fields.push(fieldText("wifiDismissSsid","Wi-Fi SSID",editorDraft.wifiDismissSsid,"Android checks the connected Wi-Fi network.","wide"));
  if (c === "HANDWRITING") fields.push(fieldText("photoMatchUri","Reference URI",editorDraft.photoMatchUri,"Android-only reference resource.","wide"));
  return fields.join("");
}

function renderSection(tab) {
  const d = editorDraft;
  if (tab === "overview") {
    return sectionCard("Alarm identity","The same core controls you use when creating an alarm on Android.",
      fieldText("label","Label",d.label,"Shown on the alarm card and firing screen.",true,"Study / Wake up") +
      fieldText("group","Group",d.group,"Optional grouping such as Work, School, Gym or Personal.") +
      fieldSelect("isEnabled","Enabled",String(Boolean(d.isEnabled)),[["true","Yes"],["false","No"]]) +
      fieldNumber("hour","Hour",d.hour,0,23,1,"24-hour cloud representation.") +
      fieldNumber("minute","Minute",d.minute,0,59,1) +
      fieldTextarea("morningRoutine","Morning routine",d.morningRoutine,"One item per line; Android keeps the routine with the alarm.",true)
    ) + repeatDaysEditor();
  }

  if (tab === "sound") {
    return sectionCard("Sound","Alarm audio, volume, vibration and ringtone behavior.",
      ringtoneField(d.ringtoneUri) +
      fieldNumber("volume","Alarm volume",d.volume,0,100,1) +
      fieldSelect("vibrationIntensity","Vibration intensity",String(d.vibrationIntensity),[["0","Off"],["1","Gentle"],["2","Intense"]]) +
      fieldSelect("vibrationPattern","Vibration pattern",d.vibrationPattern,[["default","Default"],["gentle","Gentle"],["heartbeat","Heartbeat"],["escalating","Escalating"],["sos","SOS"]]) +
      fieldNumber("gradualVolumeSeconds","Gradual volume",d.gradualVolumeSeconds,0,300,1,"Fade-in duration in seconds.") +
      fieldNumber("vibrationDelaySeconds","Vibration delay",d.vibrationDelaySeconds,0,600,1,"Seconds before vibration begins.") +
      fieldSwitch("vibrationEnabled","Vibration enabled",d.vibrationEnabled) +
      fieldSwitch("overrideSystemVolume","Override system volume",d.overrideSystemVolume,"Use the alarm's volume instead of the current device media volume.") +
      fieldSwitch("dismissAtRingtoneEnd","Dismiss at ringtone end",d.dismissAtRingtoneEnd,"Android ends the alarm after the selected track finishes; ignored for internet radio.") +
      fieldTextarea("ringtonePool","Ringtone pool",d.ringtonePool,"Comma-separated Android ringtone URIs. A random entry is chosen at fire time.",true) +
      fieldText("spotifyUri","Spotify URI",d.spotifyUri,"Optional Spotify/track URI used by Android.",true) +
      fieldText("internetRadioUrl","Internet radio URL",d.internetRadioUrl,"Use an HTTPS stream URL; Android plays it as the alarm sound.",true)
    );
  }

  if (tab === "dismiss") {
    return sectionCard("Snooze & dismissal","Anti-snooze and dismissal rules match the native alarm model.",
      fieldNumber("snoozeDurationMinutes","Snooze duration",d.snoozeDurationMinutes,1,180,1,"Minutes.") +
      fieldNumber("maxSnoozeCount","Max snoozes",d.maxSnoozeCount,0,20,1,"0 means unlimited.") +
      fieldSwitch("progressiveSnooze","Progressive snooze",d.progressiveSnooze,"Each snooze shortens the next snooze by one minute.") +
      fieldNumber("earlyDismissMinutes","Early dismiss",d.earlyDismissMinutes,0,180,1,"Minutes before scheduled fire when dismissal becomes available.") +
      fieldSwitch("holdToDismissEnabled","Hold to dismiss",d.holdToDismissEnabled,"Require a deliberate hold gesture on the Android firing screen.") +
      fieldSwitch("showOnLockScreen","Show on lock screen",d.showOnLockScreen) +
      fieldSwitch("backupSoundEnabled","Backup sound escalation",d.backupSoundEnabled,"Escalate to the backup sound after the configured delay.") +
      fieldNumber("backupSoundDelaySec","Backup sound delay",d.backupSoundDelaySec,5,900,1,"Seconds.") +
      fieldSelect("hardwareButtonAction","Hardware-button action",d.hardwareButtonAction,[["NONE","None"],["SNOOZE","Snooze"],["DISMISS","Dismiss"]])
    ) + sectionCard("Dismiss challenge","Pick the same challenge type available in Android.",
      fieldSelect("challengeType","Challenge",d.challengeType,CHALLENGES,true) +
      fieldTextarea("challengeChain","Mission chain",d.challengeChain,"Comma-separated challenge names; Android runs them in sequence.",true) +
      challengeFields()
    );
  }

  if (tab === "schedule") {
    return sectionCard("Schedule","Dates, smart alarms, solar anchors, shifts and timezone policy.",
      fieldText("specificDate","Specific date",d.specificDate,"ISO local date YYYY-MM-DD. Leave blank to use repeat days.",true,"2026-10-05") +
      fieldSwitch("smartAlarmEnabled","Smart alarm",d.smartAlarmEnabled,"Android may fire inside the light-sleep window.") +
      fieldNumber("smartAlarmWindowMinutes","Smart window",d.smartAlarmWindowMinutes,0,60,1,"Minutes.") +
      fieldSwitch("skipOnHolidays","Skip public holidays",d.skipOnHolidays) +
      fieldNumber("weatherEarlyMinutes","Weather early offset",d.weatherEarlyMinutes,0,60,1,"Minutes earlier under the Android weather policy.") +
      fieldSelect("solarAnchor","Solar anchor",d.solarAnchor,[["SUNRISE","Sunrise"],["SUNSET","Sunset"]]) +
      fieldNumber("solarOffsetMinutes","Solar offset",d.solarOffsetMinutes,-720,720,1,"Negative = before anchor, positive = after anchor.") +
      fieldSelect("shiftPattern","Shift pattern",d.shiftPattern,[["","Disabled"],["DDNNO","DDNNO"],["FOUR_ON_FOUR_OFF","Four on / four off"],["PANAMA","Panama"],["DUPONT","DuPont"],["PITMAN","Pitman"]]) +
      fieldText("shiftPatternStartDate","Shift start date",d.shiftPatternStartDate,"ISO date marking day zero of the shift pattern.",false,"YYYY-MM-DD") +
      fieldSelect("timezonePolicy","Timezone policy",d.timezonePolicy,[["LOCAL","Follow device timezone"],["FIXED","Keep fixed timezone"]]) +
      fieldText("fixedTimezoneId","Fixed IANA timezone",d.fixedTimezoneId,"Example: Asia/Kolkata. Only used when policy is FIXED.",true,"Asia/Kolkata")
    ) + repeatDaysEditor();
  }

  if (tab === "wake") {
    return sectionCard("Wake effects","Visual and announcement behavior on Android.",
      fieldSwitch("flashWake","Flash wake",d.flashWake,"Gradually increases screen brightness.") +
      fieldSwitch("flashlightStrobe","Flashlight strobe",d.flashlightStrobe,"Uses the Android flashlight during alarm firing.") +
      fieldSwitch("ttsEnabled","Morning announcement",d.ttsEnabled,"Android announces the configured wake message.") +
      fieldSwitch("wakeConfirmEnabled","Wake confirmation",d.wakeConfirmEnabled,"Requires a later wake confirmation.") +
      fieldNumber("wakeConfirmDelayMinutes","Wake confirmation delay",d.wakeConfirmDelayMinutes,1,180,1,"Minutes after dismissal.") +
      fieldSwitch("sunriseSimulation","Sunrise simulation",d.sunriseSimulation) +
      fieldNumber("sunriseMinutes","Sunrise duration",d.sunriseMinutes,0,120,1,"Minutes.") +
      fieldSwitch("firingBackgroundImageEnabled","Firing background image",d.firingBackgroundImageEnabled,"Android shows the selected per-alarm image.") +
      fieldText("firingBackgroundImageUri","Background image URI",d.firingBackgroundImageUri,"Android device-local URI; the web cannot upload the file here.",true,"content://…") +
      fieldSwitch("firingBackgroundBlurEnabled","Background blur",d.firingBackgroundBlurEnabled,"Android 12+ applies the firing-screen blur.")
    );
  }

  if (tab === "integrations") {
    return sectionCard("Integrations","External actions and device integrations supported by Android.",
      fieldText("spotifyUri","Spotify URI",d.spotifyUri,"Optional Spotify source.",true) +
      fieldSwitch("hueEnabled","Philips Hue sunrise",d.hueEnabled,"Android uses the configured Hue bridge.") +
      fieldNumber("huePreWakeMinutes","Hue pre-wake",d.huePreWakeMinutes,0,180,1,"Minutes before the alarm.") +
      fieldText("dismissActionPayload","Dismiss action payload",d.dismissActionPayload,"Webhook URL, Hue scene name, or broadcast action depending on the type.",true) +
      fieldSelect("dismissActionType","Dismiss action type",d.dismissActionType,[["NONE","None"],["WEBHOOK","Webhook"],["HUE_SCENE","Hue scene"],["BROADCAST","Broadcast"]]) +
      fieldSwitch("guardianEnabled","Guardian Angel",d.guardianEnabled,"Android can escalate when the alarm is not dismissed.") +
      fieldText("guardianPhone","Guardian phone",d.guardianPhone,"Phone number used by Android escalation.",false) +
      fieldNumber("guardianDelaySec","Guardian delay",d.guardianDelaySec,30,3600,1,"Seconds.") +
      fieldText("locationDismissSsid","Wi-Fi SSID",d.wifiDismissSsid,"Network used by the Wi-Fi dismiss challenge.",true) +
      fieldText("internetRadioUrl","Internet radio URL",d.internetRadioUrl,"HTTPS stream URL.",true)
    ) + sectionCard("Location unlock","Android-only location-based auto-dismiss.",
      fieldSwitch("locationDismissEnabled","Location dismiss enabled",d.locationDismissEnabled) +
      fieldNumber("locationDismissLat","Latitude",d.locationDismissLat,-90,90,.000001) +
      fieldNumber("locationDismissLng","Longitude",d.locationDismissLng,-180,180,.000001) +
      fieldNumber("locationDismissRadius","Radius",d.locationDismissRadius,25,5000,1,"Meters.")
    );
  }

  return sectionCard("Advanced","Less frequently used Android-compatible fields; no raw JSON required.",
    fieldText("profileName","Alarm profile",d.profileName,"Optional named profile.") +
    fieldNumber("sortOrder","Sort order",d.sortOrder,0,2147483647,1,"0 means unassigned.") +
    fieldText("wifiDismissSsid","Wi-Fi dismiss SSID",d.wifiDismissSsid,"SSID used by the Wi-Fi challenge.",true) +
    fieldText("nfcTagId","NFC tag ID",d.nfcTagId,"Android NFC reference.",true) +
    fieldText("barcodeValue","Barcode / QR value",d.barcodeValue,"Android barcode reference.",true) +
    fieldText("photoMatchUri","Photo match URI",d.photoMatchUri,"Android local reference URI.",true) +
    fieldTextarea("morningRoutine","Morning routine",d.morningRoutine,"One item per line. Android shows these after waking.",true) +
    fieldText("firingBackgroundImageUri","Background image URI",d.firingBackgroundImageUri,"Android local reference URI.",true)
  );
}

function bindEditorEvents() {
  $("alarmEditor").querySelectorAll("[data-field]").forEach(el => {
    const key = el.dataset.field;
    const update = () => {
      if (el.type === "checkbox") editorDraft[key] = el.checked;
      else if (el.tagName === "SELECT") {
        if (key === "isEnabled") editorDraft[key] = el.value === "true";
        else if (["hour","minute","volume","vibrationIntensity","gradualVolumeSeconds","vibrationDelaySeconds","snoozeDurationMinutes","maxSnoozeCount","backupSoundDelaySec","earlyDismissMinutes","smartAlarmWindowMinutes","weatherEarlyMinutes","solarOffsetMinutes","huePreWakeMinutes","wakeConfirmDelayMinutes","sunriseMinutes","guardianDelaySec","locationDismissLat","locationDismissLng","locationDismissRadius","walkStepsRequired","requiredSquats","sortOrder"].includes(key)) editorDraft[key] = num(el.value, editorDraft[key]);
        else editorDraft[key] = el.value;
      } else if (el.type === "number") editorDraft[key] = num(el.value, editorDraft[key]);
      else editorDraft[key] = el.value;
      renderPreview();
      if (key === "challengeType") renderEditor();
    };
    el.addEventListener("input", update);
    el.addEventListener("change", update);
  });
  $("alarmEditor").querySelectorAll("[data-ringtone-preset]").forEach(select => {
    select.addEventListener("change", () => {
      editorDraft.ringtoneUri = select.value;
      renderEditor();
    });
  });
  $("alarmEditor").querySelectorAll("[data-day]").forEach(btn => {
    btn.addEventListener("click", () => {
      const day = btn.dataset.day;
      const current = new Set(editorDraft.repeatDays || []);
      current.has(day) ? current.delete(day) : current.add(day);
      editorDraft.repeatDays = DAYS.map(x=>x[0]).filter(x=>current.has(x));
      renderEditor();
    });
  });
}

function renderEditor() {
  const tabs = EDITOR_TABS.map(([key,label]) => `<button class="editor-tab ${editorTab === key ? "active" : ""}" type="button" data-editor-tab="${key}">${label}</button>`).join("");
  $("alarmEditor").innerHTML = `
    <div class="editor-nav">${tabs}</div>
    <div class="editor-section">${renderSection(editorTab)}</div>`;
  $("alarmEditor").querySelectorAll("[data-editor-tab]").forEach(btn => {
    btn.addEventListener("click", () => { editorTab = btn.dataset.editorTab; renderEditor(); });
  });
  bindEditorEvents();
  renderPreview();
}

function applyNumericBoundsBeforeSave(payload) {
  const ranges = {
    hour:[0,23], minute:[0,59], volume:[0,100], vibrationIntensity:[0,2],
    gradualVolumeSeconds:[0,300], vibrationDelaySeconds:[0,600], snoozeDurationMinutes:[1,180],
    maxSnoozeCount:[0,20], walkStepsRequired:[1,10000], requiredSquats:[1,100],
    wakeConfirmDelayMinutes:[1,180], smartAlarmWindowMinutes:[0,60], huePreWakeMinutes:[0,180],
    backupSoundDelaySec:[5,900], sunriseMinutes:[0,120], earlyDismissMinutes:[0,180],
    guardianDelaySec:[30,3600], locationDismissRadius:[25,5000], weatherEarlyMinutes:[0,60],
    solarOffsetMinutes:[-720,720], sortOrder:[0,2147483647]
  };
  Object.entries(ranges).forEach(([key,[lo,hi]]) => {
    payload[key] = Math.max(lo, Math.min(hi, num(payload[key], lo)));
  });
  const ringtoneValue = String(payload.ringtoneUri || "").trim();
  payload.ringtoneUri = ["default", "default_alarm", "system_default"].includes(ringtoneValue.toLowerCase())
    ? ""
    : ringtoneValue.toLowerCase() === "silent"
      ? "silent"
      : ringtoneValue;
  payload.label = String(payload.label || "").trim().slice(0,120);
  payload.group = String(payload.group || "").trim().slice(0,40);
  payload.profileName = String(payload.profileName || "").trim().slice(0,40);
  payload.guardianPhone = String(payload.guardianPhone || "").trim().slice(0,40);
  payload.dismissActionPayload = String(payload.dismissActionPayload || "").trim().slice(0,2048);
  payload.repeatDays = DAYS.map(x=>x[0]).filter(x => Array.isArray(payload.repeatDays) && payload.repeatDays.includes(x));
  payload.isEnabled = Boolean(payload.isEnabled);
  payload.timezonePolicy = payload.timezonePolicy === "FIXED" ? "FIXED" : "LOCAL";
  payload.solarAnchor = payload.solarAnchor === "SUNSET" ? "SUNSET" : "SUNRISE";
  return payload;
}

async function saveAlarm(event) {
  event.preventDefault();
  const payload = applyNumericBoundsBeforeSave(clone(editorDraft));
  const id = state.editing?.id || crypto.randomUUID();
  try {
    const remote = await api(`/api/alarms/${id}`, {
      method: "PUT",
      body: JSON.stringify({ payload, expectedVersion: state.editing?.version || 0 })
    });
    const index = state.alarms.findIndex(a => a.id === id);
    if (index >= 0) state.alarms[index] = remote;
    else state.alarms.push(remote);
    state.editing = null;
    $("alarmDialog").close();
    renderAlarms();
    $("syncState").textContent = "synced";
    refreshActivity();
  } catch (error) {
    if (error.message === "version_conflict" || error.message === "alarm_deleted_conflict") {
      await refreshAfterConflict();
      if ($("alarmDialog").open) $("alarmDialog").close();
      alert("This alarm changed on another device. The latest version was loaded and your stale edit was not applied.");
      return;
    }
    alert(error.message.replaceAll("_", " "));
  }
}

async function deleteAlarm() {
  const id = state.editing?.id;
  if (!id) return;
  if (!confirm("delete this alarm?")) return;
  try {
    await api(`/api/alarms/${id}?expectedVersion=${encodeURIComponent(state.editing?.version || 0)}`, { method: "DELETE" });
    state.alarms = state.alarms.filter(a => a.id !== id);
    state.editing = null;
    $("alarmDialog").close();
    renderAlarms();
    refreshActivity();
  } catch (error) {
    if (error.message === "version_conflict" || error.message === "alarm_deleted_conflict") {
      await refreshAfterConflict();
      if ($("alarmDialog").open) $("alarmDialog").close();
      alert("This alarm changed or was deleted on another device. The latest version was loaded and your stale delete was not applied.");
      return;
    }
    alert(error.message.replaceAll("_", " "));
  }
}

function refreshActivity() {
  return api("/api/audit").then(data => {
    $("activityList").innerHTML = data.events.map(e => `<div class="list-item"><span>${escapeHtml(e.action)} · ${escapeHtml(e.entityType)} ${escapeHtml(e.entityId || "")}</span><span class="muted">${escapeHtml(new Date(e.createdAt).toLocaleString())}</span></div>`).join("");
  }).catch(() => {});
}

let timer = { running:false, remaining:300000, last:0 };
function renderTimer() {
  const ms = Math.max(0, timer.remaining);
  const total = Math.floor(ms / 1000), m = Math.floor(total/60), s = total%60;
  $("timerDisplay").textContent = `${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}:${String(Math.floor(ms%1000/10)).padStart(2,"0")}`;
}
setInterval(() => {
  const t = performance.now();
  if (timer.running) {
    timer.remaining = Math.max(0, timer.remaining - (t - timer.last));
    timer.last = t;
    if (timer.remaining === 0) {
      timer.running = false;
      try { new Audio("data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAACJWAAACABAAZGF0YQAAAAA=").play(); } catch {}
    }
  }
  renderTimer();
}, 100);
$("timerStart").onclick = () => {
  if (!timer.running) {
    if (timer.remaining <= 0) timer.remaining = (num($("timerMinutes").value,0)*60+num($("timerSeconds").value,0))*1000;
    timer.running = true; timer.last = performance.now();
  }
};
$("timerPause").onclick = () => timer.running = false;
$("timerReset").onclick = () => {
  timer.running = false;
  timer.remaining = (num($("timerMinutes").value,0)*60+num($("timerSeconds").value,0))*1000;
};
renderTimer();

let sw = { running:false, started:0, elapsed:0 };
setInterval(() => {
  const now = performance.now();
  if (sw.running) sw.elapsed = now - sw.started;
  const cs = Math.floor(sw.elapsed / 10), s = Math.floor(cs/100)%60, m = Math.floor(cs/6000);
  $("stopwatchDisplay").textContent = `${String(Math.floor(m/60)).padStart(2,"0")}:${String(m%60).padStart(2,"0")}:${String(s).padStart(2,"0")}.${String(cs%100).padStart(2,"0")}`;
}, 30);
$("swStart").onclick = () => {
  if (!sw.running) { sw.running = true; sw.started = performance.now() - sw.elapsed; }
  else sw.running = false;
  $("swStart").textContent = sw.running ? "pause" : "start";
};
$("swLap").onclick = () => {
  const item = document.createElement("div");
  item.className = "list-item";
  item.textContent = new Date().toLocaleTimeString() + " · " + $("stopwatchDisplay").textContent;
  $("laps").prepend(item);
};
$("swReset").onclick = () => { sw = { running:false, started:0, elapsed:0 }; $("swStart").textContent = "start"; $("laps").innerHTML = ""; };

function renderWorld() {
  $("worldList").innerHTML = state.worldZones.map(zone => {
    const now = new Intl.DateTimeFormat(undefined, {timeZone:zone,weekday:"short",month:"short",day:"numeric",hour:"2-digit",minute:"2-digit",second:"2-digit"}).format(new Date());
    return `<div class="card"><h2>${escapeHtml(zone)}</h2><div class="alarm-time">${escapeHtml(now)}</div><button class="danger" type="button" data-zone="${escapeAttr(zone)}">remove</button></div>`;
  }).join("");
  $("worldList").querySelectorAll("[data-zone]").forEach(btn => {
    btn.onclick = () => { state.worldZones = state.worldZones.filter(z => z !== btn.dataset.zone); renderWorld(); };
  });
}
$("timezonePicker").onchange = (event) => {
  const zone = event.target.value;
  if (!state.worldZones.includes(zone)) state.worldZones.push(zone);
  renderWorld();
};
setInterval(renderWorld, 1000);
renderWorld();

$("authForm").addEventListener("submit", authSubmit);
$("loginTab").onclick = () => switchAuth("login");
$("registerTab").onclick = () => switchAuth("register");
$("logoutBtn").onclick = () => {
  state.token = ""; state.user = null; state.alarms = []; state.editing = null;
  localStorage.removeItem("acx_token"); localStorage.removeItem(cursorStorageKey()); showAuth();
};
$("syncBtn").onclick = () => syncNow().catch(() => {});
$("newAlarmBtn").onclick = () => openAlarm();
$("alarmForm").addEventListener("submit", saveAlarm);
$("deleteAlarmBtn").onclick = deleteAlarm;
$("exportBtn").onclick = () => {
  const blob = new Blob([JSON.stringify(state.alarms.map(x => x.payload), null, 2)], {type:"application/json"});
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a"); a.href = url; a.download = "alarmclockxtreme-alarms.json"; a.click();
  URL.revokeObjectURL(url);
};
$("aiRun").onclick = async () => {
  const command = $("aiCommand").value.trim();
  if (!command) return;
  $("aiStatus").textContent = "thinking…"; $("aiResult").textContent = "";
  try {
    const data = await api("/api/ai/command", {method:"POST",body:JSON.stringify({command})});
    $("aiResult").textContent = JSON.stringify(data, null, 2);
    $("aiStatus").textContent = "done";
    await syncNow({forceFull:true});
    refreshActivity();
  } catch (error) {
    $("aiResult").textContent = error.message.replaceAll("_"," ");
    $("aiStatus").textContent = "error";
  }
};

document.querySelectorAll(".nav-btn").forEach(btn => {
  btn.onclick = () => {
    document.querySelectorAll(".nav-btn").forEach(x => x.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach(x => x.classList.remove("active"));
    btn.classList.add("active");
    $(`tab-${btn.dataset.tab}`).classList.add("active");
  };
});

function clockTick() {
  const now = new Date();
  $("clock").textContent = now.toLocaleTimeString([], {hour12:false});
  $("date").textContent = now.toLocaleDateString(undefined, {weekday:"long",year:"numeric",month:"long",day:"numeric"});
}
setInterval(clockTick, 250);
clockTick();

function startPolling() {
  if (syncTimer) clearInterval(syncTimer);
  syncTimer = setInterval(() => {
    if (state.token && document.visibilityState === "visible") {
      syncNow({silent:true}).catch(() => {});
    }
  }, 30000);
}
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && state.token) syncNow({silent:true}).catch(() => {});
});

(async function boot() {
  startPolling();
  if (!state.token) return showAuth();
  try {
    const me = await api("/api/me");
    state.user = me.user;
    await refreshCloudDataset();
    showApp();
    await syncNow({forceFull:true});
    renderWorld();
    refreshActivity();
  } catch {
    showAuth();
  }
})();