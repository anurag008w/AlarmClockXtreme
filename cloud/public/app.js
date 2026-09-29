const state = {
  token: localStorage.getItem("acx_token") || "",
  user: null,
  alarms: [],
  editing: null,
  loginMode: true,
  worldZones: ["Asia/Kolkata", "Europe/London", "America/New_York"]
};

const $ = (id) => document.getElementById(id);

function setAuthError(message = "") { $("authError").textContent = message; }
function headers() {
  return { "content-type": "application/json", ...(state.token ? { authorization: `Bearer ${state.token}` } : {}) };
}
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { ...headers(), ...(options.headers || {}) } });
  const body = await response.json().catch(() => ({}));
  if (response.status === 401) {
    state.token = "";
    localStorage.removeItem("acx_token");
    showAuth();
  }
  if (!response.ok) throw new Error(body.error || `request_failed_${response.status}`);
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
    localStorage.setItem("acx_token", state.token);
    showApp();
    await syncNow();
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

async function syncNow() {
  $("syncState").textContent = "syncing…";
  try {
    let cursor = localStorage.getItem("acx_cursor") || new Date(0).toISOString();
    const data = await api(`/api/alarms?since=${encodeURIComponent(cursor)}`);
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
    localStorage.setItem("acx_cursor", data.cursor);
    state.alarms.sort((a,b) => String(a.payload?.hour ?? "").localeCompare(String(b.payload?.hour ?? "")));
    $("syncState").textContent = "synced";
    renderAlarms();
  } catch (error) {
    $("syncState").textContent = "sync error";
    if (error.message === "missing_token") showAuth();
  }
}

function openAlarm(remote = null) {
  state.editing = remote;
  const payload = remote?.payload || {
    hour: 7, minute: 0, label: "", isEnabled: true, repeatDays: [],
    volume: 100, vibrationEnabled: true, vibrationIntensity: 2,
    challengeType: "NONE", snoozeDurationMinutes: 10, maxSnoozeCount: 3
  };
  $("dialogTitle").textContent = remote ? "edit alarm" : "new alarm";
  $("alarmTime").value = `${String(payload.hour ?? 0).padStart(2,"0")}:${String(payload.minute ?? 0).padStart(2,"0")}`;
  $("alarmLabel").value = payload.label || "";
  $("alarmDays").value = Array.isArray(payload.repeatDays) ? payload.repeatDays.join(",") : "";
  $("alarmChallenge").value = payload.challengeType || "NONE";
  $("alarmVolume").value = payload.volume ?? 100;
  $("alarmEnabled").value = String(Boolean(payload.isEnabled));
  $("alarmAdvanced").value = JSON.stringify(payload, null, 2);
  $("deleteAlarmBtn").classList.toggle("hidden", !remote);
  $("alarmDialog").showModal();
}

async function saveAlarm(event) {
  event.preventDefault();
  let payload;
  try {
    payload = JSON.parse($("alarmAdvanced").value || "{}");
  } catch {
    alert("advanced alarm JSON is invalid");
    return;
  }
  const [hour, minute] = $("alarmTime").value.split(":").map(Number);
  payload.hour = hour;
  payload.minute = minute;
  payload.label = $("alarmLabel").value.trim();
  payload.repeatDays = $("alarmDays").value.split(",").map(x => x.trim()).filter(Boolean);
  payload.challengeType = $("alarmChallenge").value;
  payload.volume = Number($("alarmVolume").value);
  payload.isEnabled = $("alarmEnabled").value === "true";

  const id = state.editing?.id || crypto.randomUUID();
  try {
    const remote = await api(`/api/alarms/${id}`, {
      method: "PUT",
      body: JSON.stringify({ payload, expectedVersion: state.editing?.version || 0 })
    });
    const index = state.alarms.findIndex(a => a.id === id);
    if (index >= 0) state.alarms[index] = remote;
    else state.alarms.push(remote);
    localStorage.setItem("acx_cursor", remote.updatedAt);
    $("alarmDialog").close();
    renderAlarms();
    $("syncState").textContent = "synced";
    refreshActivity();
  } catch (error) {
    alert(error.message.replaceAll("_", " "));
  }
}

async function deleteAlarm() {
  const id = state.editing?.id;
  if (!id) return;
  if (!confirm("delete this alarm?")) return;
  try {
    await api(`/api/alarms/${id}`, { method: "DELETE" });
    state.alarms = state.alarms.filter(a => a.id !== id);
    $("alarmDialog").close();
    renderAlarms();
    refreshActivity();
  } catch (error) {
    alert(error.message.replaceAll("_", " "));
  }
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
    const days = Array.isArray(p.repeatDays) && p.repeatDays.length ? p.repeatDays.join(" · ") : "once";
    return `<article class="card alarm">
      <div class="alarm-time">${escapeHtml(time)}</div>
      <div class="alarm-label">${escapeHtml(p.label || "untitled alarm")}</div>
      <div class="alarm-meta">${escapeHtml(days)} · challenge: ${escapeHtml(p.challengeType || "NONE")} · volume ${escapeHtml(p.volume ?? 100)} · ${p.isEnabled ? "enabled" : "disabled"}</div>
      <div class="alarm-actions">
        <button class="secondary" data-edit="${remote.id}">edit</button>
        <button class="ghost" data-toggle="${remote.id}">${p.isEnabled ? "disable" : "enable"}</button>
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
      await api(`/api/alarms/${remote.id}`, {
        method: "PUT",
        body: JSON.stringify({
          payload: { ...remote.payload, isEnabled: !remote.payload.isEnabled },
          expectedVersion: remote.version
        })
      });
      await syncNow();
    })
  );
}

function escapeHtml(value) {
  return String(value).replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;");
}

let timer = { running:false, remaining:300000, last:0 };
function renderTimer() {
  let ms = Math.max(0, timer.remaining);
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60), s = total % 60;
  $("timerDisplay").textContent = `${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}:${String(Math.floor(ms%1000/10)).padStart(2,"0")}`;
}
setInterval(() => {
  const t = performance.now();
  if (timer.running) {
    const delta = t - timer.last;
    timer.last = t;
    timer.remaining = Math.max(0, timer.remaining - delta);
    if (timer.remaining === 0) {
      timer.running = false;
      try { new Audio("data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAACJWAAACABAAZGF0YQAAAAA=").play(); } catch {}
    }
  }
  renderTimer();
}, 100);
$("timerStart").onclick = () => {
  if (!timer.running) {
    const m = Number($("timerMinutes").value || 0);
    const s = Number($("timerSeconds").value || 0);
    if (timer.remaining <= 0) timer.remaining = (m*60+s)*1000;
    timer.running = true; timer.last = performance.now();
  }
};
$("timerPause").onclick = () => timer.running = false;
$("timerReset").onclick = () => { timer.running = false; timer.remaining = (Number($("timerMinutes").value || 0)*60 + Number($("timerSeconds").value || 0))*1000; };
renderTimer();

let sw = { running:false, started:0, elapsed:0 };
setInterval(() => {
  const now = performance.now();
  if (sw.running) sw.elapsed = now - sw.started;
  const cs = Math.floor(sw.elapsed / 10);
  const s = Math.floor(cs / 100) % 60;
  const m = Math.floor(cs / 6000);
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
  item.textContent = new Date().toLocaleTimeString() + " · " + ($("stopwatchDisplay").textContent || "");
  $("laps").prepend(item);
};
$("swReset").onclick = () => { sw = { running:false, started:0, elapsed:0 }; $("swStart").textContent = "start"; $("laps").innerHTML = ""; };

function renderWorld() {
  $("worldList").innerHTML = state.worldZones.map(zone => {
    const now = new Intl.DateTimeFormat(undefined, { timeZone: zone, weekday:"short", month:"short", day:"numeric", hour:"2-digit", minute:"2-digit", second:"2-digit" }).format(new Date());
    return `<div class="card"><h2>${escapeHtml(zone)}</h2><div class="alarm-time">${escapeHtml(now)}</div><button class="danger" data-zone="${escapeHtml(zone)}">remove</button></div>`;
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

async function refreshActivity() {
  try {
    const data = await api("/api/audit");
    $("activityList").innerHTML = data.events.map(e => `<div class="list-item"><span>${escapeHtml(e.action)} · ${escapeHtml(e.entityType)} ${escapeHtml(e.entityId || "")} · ${escapeHtml(e.source)}</span><span class="muted">${escapeHtml(new Date(e.createdAt).toLocaleString())}</span></div>`).join("");
  } catch {}
}

$("authForm").addEventListener("submit", authSubmit);
$("loginTab").onclick = () => switchAuth("login");
$("registerTab").onclick = () => switchAuth("register");
$("logoutBtn").onclick = () => { state.token = ""; localStorage.removeItem("acx_token"); showAuth(); };
$("syncBtn").onclick = syncNow;
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
  $("aiStatus").textContent = "thinking…";
  $("aiResult").textContent = "";
  try {
    const data = await api("/api/ai/command", { method:"POST", body: JSON.stringify({command}) });
    $("aiResult").textContent = JSON.stringify(data, null, 2);
    $("aiStatus").textContent = "done";
    await syncNow();
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
  $("date").textContent = now.toLocaleDateString(undefined, {weekday:"long", year:"numeric", month:"long", day:"numeric"});
}
setInterval(clockTick, 250);
clockTick();

(async function boot() {
  if (!state.token) return showAuth();
  try {
    const me = await api("/api/me");
    state.user = me.user;
    showApp();
    await syncNow();
    renderWorld();
    refreshActivity();
  } catch {
    showAuth();
  }
})();