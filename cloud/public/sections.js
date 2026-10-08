let newsFeeds=[];
async function loadNewsFeeds() {
 const data=await api('/api/news/feeds');newsFeeds=data.feeds;
 const match=newsFeeds.find(feed=>feed.url===phoneSettings.newsFeedUrl);
 $('newsFeed').innerHTML=newsFeeds.map(feed=>`<option value="${escapeAttr(feed.id)}">${escapeHtml(feed.label)}</option>`).join('');
 $('newsFeed').value=match?.id||'bbc';
 $('newsStatus').textContent=match ? 'Saved phone feed selected.' : 'Phone has a custom or unsynced feed. BBC is selected for reading only, not saved.';
}
async function refreshNews() {
 const button=$('newsRefresh');button.disabled=true;
 try {
  if(!newsFeeds.length)await loadNewsFeeds();
  $('newsStatus').textContent='Loading news…';
  const data=await api('/api/news?feed='+encodeURIComponent($('newsFeed').value));
  $('newsStatus').textContent=`${data.stale?'Cached news, refresh failed.':'Feed loaded.'} Fetched ${new Date(data.fetchedAtMillis).toLocaleString()}`;
  $('newsList').innerHTML=data.items.length ? data.items.map(item=>`<article class="card"><h3><a href="${escapeAttr(item.url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(item.title)}</a></h3><p>${escapeHtml(item.summary)}</p><small class="muted">${escapeHtml(new URL(item.url).hostname.replace(/^www\./,''))} · ${escapeHtml(item.published)}</small></article>`).join('') : '<p>No feed entries.</p>';
 } catch(error){$('newsStatus').textContent='Could not refresh: '+error.message+'. Any previous entries below are unchanged.';}
 finally{button.disabled=false;}
}
$('newsRefresh').onclick=refreshNews;
$('newsSave').onclick=async()=>{
 if(!phoneSettingsVersion||phoneSettingsDirty){$('newsStatus').textContent='Sync an updated phone and save or reload existing settings first.';return;}
 const feed=newsFeeds.find(feed=>feed.id===$('newsFeed').value);if(!feed)return;
 phoneSettings.newsFeedUrl=feed.url;phoneSettingsDirty=true;await savePhoneSettings();
 $('newsStatus').textContent=phoneSettingsDirty?'Feed was not saved. Check settings status.':'Feed saved in cloud; phone delivery awaits sync.';
};
function bedtimePlan(wake,goal) {
 const norm=n=>((n%1440)+1440)%1440;
 const display=n=>`${String(Math.floor(norm(n)/60)).padStart(2,'0')}:${String(norm(n)%60).padStart(2,'0')}`;
 return {suggested:display(wake-goal),cycles:[6,5,4,3].map(n=>({cycles:n,time:display(wake-n*90-15)}))};
}
function renderBedtime() {
 if(!phoneSettingsVersion){$('bedtimeSummary').textContent='Sync an updated phone first. No phone settings have been guessed.';return;}
 const [h,m]=$('bedtimeWake').value.split(':').map(Number);if(!Number.isInteger(h)||!Number.isInteger(m))return;
 const goal=phoneSettings.sleepGoalHours*60+phoneSettings.sleepGoalMinutes;
 const plan=bedtimePlan(h*60+m,goal);
 $('bedtimeSummary').innerHTML=`<p>Phone bedtime ${String(phoneSettings.bedtimeHour).padStart(2,'0')}:${String(phoneSettings.bedtimeMinute).padStart(2,'0')} · ${phoneSettings.bedtimeEnabled?'enabled':'disabled'} · reminder ${phoneSettings.bedtimeReminderMinutes} minutes before</p><p>Sleep goal ${phoneSettings.sleepGoalHours}h ${phoneSettings.sleepGoalMinutes}m. Suggested bedtime for your chosen wake time: <strong>${plan.suggested}</strong></p><p>90-minute cycle estimates (15 minutes to fall asleep): ${plan.cycles.map(row=>row.time+' ('+row.cycles+' cycles)').join(' · ')}. Estimates, not medical advice.</p>`;
 const items=phoneSettings.bedtimeChecklist.split('\n').map(s=>s.trim()).filter(Boolean);
 $('bedtimeChecklist').innerHTML=items.map((item,i)=>`<label class="switch-field"><span>${escapeHtml(item)}</span><input class="switch" type="checkbox" aria-label="${escapeAttr(item)}"></label>`).join('');
}
$('bedtimeWake').onchange=renderBedtime;
$('bedtimeSettings').onclick=()=>document.querySelector('[data-tab=settings]').click();
document.querySelector('[data-tab=news]').addEventListener('click',()=>loadNewsFeeds().catch(e=>$('newsStatus').textContent=e.message));
document.querySelector('[data-tab=bedtime]').addEventListener('click',renderBedtime);
let todaySelectedCity=null;
function renderTodayAlarms() {
 const alarms=state.alarms.filter(row=>!row.deletedAt && row.payload.isEnabled).sort((a,b)=>a.payload.hour*60+a.payload.minute-b.payload.hour*60-b.payload.minute);
 $('todayAlarms').innerHTML='<h3>Enabled cloud alarms</h3>'+(alarms.length?alarms.map(row=>`<p><strong>${String(row.payload.hour).padStart(2,'0')}:${String(row.payload.minute).padStart(2,'0')}</strong> ${escapeHtml(row.payload.label||'Alarm')}</p>`).join(''):'<p>No enabled cloud alarms.</p>');
}
$('todaySearch').onclick=async()=>{
 const button=$('todaySearch');button.disabled=true;
 try {
  const data=await api('/api/today/cities?name='+encodeURIComponent($('todayCity').value));
  $('todayCities').innerHTML=data.results.map((city,i)=>`<button type="button" class="secondary" data-city-choice="${i}">${escapeHtml(city.name)}, ${escapeHtml(city.admin1||'')} ${escapeHtml(city.country||'')}</button>`).join('');
  $('todayStatus').textContent=data.results.length?'Choose the city to read its weather.':'No cities found.';
  $('todayCities').querySelectorAll('[data-city-choice]').forEach(button=>button.onclick=()=>{todaySelectedCity=data.results[Number(button.dataset.cityChoice)];refreshTodayWeather();});
 } catch(e){$('todayStatus').textContent=e.message;}
 finally{button.disabled=false;}
};
async function refreshTodayWeather() {
 if(!todaySelectedCity){$('todayStatus').textContent='Search and select a city first.';return;}
 const button=$('todayWeatherRefresh');button.disabled=true;
 try {
  const city=todaySelectedCity;
  $('todayStatus').textContent='Loading weather…';
  const data=await api(`/api/today/weather?latitude=${encodeURIComponent(city.latitude)}&longitude=${encodeURIComponent(city.longitude)}&unit=${encodeURIComponent(phoneSettings.temperatureUnit)}`);
  const current=data.current,units=data.current_units,daily=data.daily;
  if(!current||!daily)throw Error('incomplete_forecast');
  $('todayStatus').textContent=`${city.name}: ${data.stale?'cached forecast, refresh failed':'forecast loaded'} at ${new Date(data.fetchedAtMillis).toLocaleString()}.`;
  $('todayWeather').innerHTML=`<div class="card"><h3>${escapeHtml(city.name)}</h3><p>${escapeHtml(String(current.temperature_2m))} ${escapeHtml(units.temperature_2m)} · feels like ${escapeHtml(String(current.apparent_temperature))} · humidity ${escapeHtml(String(current.relative_humidity_2m))}%</p><p>Wind ${escapeHtml(String(current.wind_speed_10m))} ${escapeHtml(units.wind_speed_10m)} · weather code ${escapeHtml(String(current.weather_code))}</p></div>`+daily.time.map((day,i)=>`<div class="card"><h3>${escapeHtml(day)}</h3><p>${escapeHtml(String(daily.temperature_2m_min[i]))} to ${escapeHtml(String(daily.temperature_2m_max[i]))} ${escapeHtml(units.temperature_2m)} · rain ${escapeHtml(String(daily.precipitation_probability_max[i]))}% · UV ${escapeHtml(String(daily.uv_index_max[i]))}</p><p>Sunrise ${escapeHtml(daily.sunrise[i])}<br>Sunset ${escapeHtml(daily.sunset[i])}</p></div>`).join('')+(data.hourly?.time ? '<div class="card"><h3>Hourly forecast</h3>'+data.hourly.time.map((time,i)=>({time,i})).filter(row=>row.time>=current.time).slice(0,12).map(({time,i})=>`<p>${escapeHtml(time)} · ${escapeHtml(String(data.hourly.temperature_2m[i]))} ${escapeHtml(units.temperature_2m)} · rain ${escapeHtml(String(data.hourly.precipitation_probability[i]))}%</p>`).join('')+'</div>' : '')+(data.airQuality?.current ? '<div class="card"><h3>Air quality and pollen</h3><p class="weather-aqi">'+(data.airQuality.current.us_aqi!=null?'US AQI <strong>'+escapeHtml(String(data.airQuality.current.us_aqi))+'</strong>':data.airQuality.current.european_aqi!=null?'European AQI <strong>'+escapeHtml(String(data.airQuality.current.european_aqi))+'</strong>':'')+'</p><details class="advanced-details"><summary>Pollutants and pollen measurements</summary>'+Object.entries(data.airQuality.current).filter(([key,value])=>key!=='interval' && value!==null).map(([key,value])=>`<p>${escapeHtml(key.replaceAll('_',' '))}: ${escapeHtml(String(value))} ${escapeHtml(data.airQuality.current_units?.[key]||'')}</p>`).join('')+'</details></div>' : '<p>Air quality unavailable for this refresh.</p>')+'<p><a href="https://open-meteo.com/" target="_blank" rel="noopener noreferrer">Weather data by Open-Meteo</a>. Forecast timezone: '+escapeHtml(data.timezone)+'.</p>';
 } catch(e){$('todayStatus').textContent='Could not refresh: '+e.message+'. Previous forecast, if any, is unchanged.';}
 finally{button.disabled=false;}
}
$('todayWeatherRefresh').onclick=refreshTodayWeather;
document.querySelector('[data-tab=today]').addEventListener('click',renderTodayAlarms);
$('dashboardPhones').onclick=async()=>{
 try { const data=await api('/api/utilities/devices');$('dashboardDevice').innerHTML='<option value="">Choose phone</option>'+data.devices.filter(d=>d.platform==='android').map(d=>`<option value="${escapeAttr(d.id)}">${escapeHtml(d.id)} (${escapeHtml(d.appVersion||'unknown')})</option>`).join(''); }
 catch(e){$('dashboardStatus').textContent=e.message;}
};
function phoneTime(value,zone){if(!value)return 'Not recorded';return new Intl.DateTimeFormat(undefined,{timeZone:zone,dateStyle:'medium',timeStyle:'short',hour12:!phoneSettings.is24HourFormat}).format(new Date(value));}
async function loadPhoneDashboard(){
 const requestToken=state.token;
 const device=$('dashboardDevice').value;
 if(!device){$('dashboardStatus').textContent='Choose a phone first.';return;}
 try {
  const data=await api('/api/dashboard/'+encodeURIComponent(device));const row=data.snapshot;
  if(state.token!==requestToken || $('dashboardDevice').value!==device)return;
  if(!row){$('dashboardStatus').textContent='No snapshot. Sync the updated Android app first.';$('phoneStats').textContent='';$('phoneAlarmDetails').textContent='';$('phoneHistory').textContent='';$('phoneToday').textContent='';$('phoneSleep').textContent='';return;}
  const p=row.payload;const age=Math.max(0,Math.floor((data.serverNowMillis-row.observedServerMillis)/1000));
  const notice=`Phone ${device}: snapshot ${age}s old, observed ${phoneTime(p.phoneObservedMillis,p.timezone)} (${p.timezone}). Not live state.`;
  $('dashboardStatus').textContent=notice;
  $('phoneToday').innerHTML=`<p>${escapeHtml(notice)}</p><h3>Phone next alarm</h3><p>${p.nextAlarm?escapeHtml(p.nextAlarm.label||'Alarm')+' · '+escapeHtml(phoneTime(p.nextAlarm.nextTriggerTime,p.timezone)):'No future phone trigger in this snapshot.'}</p><h3>Phone calendar (${escapeHtml(p.calendarDate)})</h3><p>${escapeHtml(p.calendarStatus.replaceAll('_',' '))}</p>`+p.calendar.map(e=>`<p><strong>${escapeHtml(e.title)}</strong><br>${e.allDay?'All day':escapeHtml(phoneTime(e.startTime,p.timezone))+' to '+escapeHtml(phoneTime(e.endTime,p.timezone))}<br>${escapeHtml(e.location)}</p>`).join('')+(p.location?`<p>Saved phone weather location: ${escapeHtml(p.location.name)}. Not live GPS.</p><button id="usePhoneWeather" class="secondary" type="button">Read weather for saved phone location</button>`:'<p>No saved weather location recorded.</p>');
  const weather=$('usePhoneWeather');if(weather)weather.onclick=()=>{todaySelectedCity=p.location;refreshTodayWeather();};
  if(p.location && !todaySelectedCity && document.querySelector('[data-tab=today]').classList.contains('active')) { todaySelectedCity=p.location; $('todayCity').value=p.location.name; refreshTodayWeather(); }
  $('phoneStats').innerHTML='<div class="card"><h3>Phone totals</h3><div class="stats-metrics">'+Object.entries(p.stats).filter(([,value])=>typeof value!=='object').map(([key,value])=>`<div class="metric"><span>${escapeHtml(key.replace(/([A-Z])/g,' $1'))}</span><strong>${escapeHtml(String(value))}</strong></div>`).join('')+'</div><h3>Weekday counts and response</h3><div class="stats-week">'+Object.entries(p.stats.dayOfWeekCounts).map(([day,value])=>`<p>Day ${escapeHtml(day)}: ${value} events · average ${escapeHtml(String(p.stats.dayOfWeekAvgResponseSec[day]??'not recorded'))} seconds</p>`).join('')+'</div></div>';
  $('phoneAlarmDetails').innerHTML='<h3>Per-alarm 30-day stats and phone challenge readiness</h3>'+(p.alarmDetails||[]).map(a=>`<article class="card"><h3>${escapeHtml(a.label||'Alarm')}</h3><p>Cloud ID ${escapeHtml(a.cloudAlarmId||'not mapped')} · ${a.fireCount} events · ${a.missedCount} missed · average ${escapeHtml(String(a.avgSnoozesPerFire))} snoozes · ${a.avgDismissTimeSec}s response</p><p>${escapeHtml(a.readiness.replaceAll('_',' '))}${a.blocksSave?' (blocks native save)':''}<br>${escapeHtml(a.readinessMessage||'')}</p>${a.canSkipNext&&a.cloudAlarmId?`<p>Phone occurrence ${escapeHtml(phoneTime(a.nextTriggerTime,p.timezone))}</p><button class="secondary" type="button" data-skip-next="${escapeAttr(a.cloudAlarmId)}">Skip this next occurrence</button>`:''}</article>`).join('')+'<p>Readiness is a phone snapshot, not a live guarantee. Open Android to grant missing permissions/register references.</p>';
  $('phoneAlarmDetails').querySelectorAll('[data-skip-next]').forEach(button=>button.onclick=async()=>{
   const alarm=p.alarmDetails.find(a=>a.cloudAlarmId===button.dataset.skipNext);
   if(state.token!==requestToken || $('dashboardDevice').value!==device)return;
   if(!confirm(`Skip ${alarm.label||'Alarm'} on phone ${device} at ${phoneTime(alarm.nextTriggerTime,p.timezone)} (${p.timezone})? A stale or near-due occurrence is rejected. This does not disable the repeating alarm.`))return;
   button.disabled=true;const commandId='v2-'+Date.now()+'-'+crypto.randomUUID().replaceAll('-','');
   try {const row=await api('/api/utilities/commands',{method:'POST',body:JSON.stringify({deviceId:device,commandId,kind:'alarm',action:'skip-next',payload:{cloudAlarmId:alarm.cloudAlarmId,expectedNextTriggerTime:alarm.nextTriggerTime}})});$('dashboardStatus').textContent=`${row.status}: ${commandId}. Queued only. Refresh command status below for phone receipt.`;}
   catch(error){$('dashboardStatus').textContent=`${error.message}. Check command ${commandId} before retrying.`;}
  });
  renderPhoneSleep(p.sleep,notice);
  $('phoneHistory').innerHTML='<h3>Recent alarm events (up to 50)</h3>'+p.events.map(e=>`<article class="card"><h3>${escapeHtml(e.alarmLabel||'Alarm')}</h3><p>${escapeHtml(e.action)} · fired ${escapeHtml(phoneTime(e.firedAt,p.timezone))}</p><p>Action ${escapeHtml(phoneTime(e.actionAt,p.timezone))} · snoozes ${e.snoozeCount} · ${escapeHtml(e.challengeType)} · solve ${e.challengeSolveTimeMs}ms · retries ${e.challengeRetryCount}</p></article>`).join('');
 } catch(e){if(state.token!==requestToken)return;$('dashboardStatus').textContent='Snapshot refresh failed: '+e.message+'. Previously displayed data is unchanged.';}
}
$('refreshAlarmCommands').onclick=async()=>{const device=$('dashboardDevice').value;const token=state.token;if(!device)return;try{const data=await api('/api/utilities/'+encodeURIComponent(device));if(state.token!==token||$('dashboardDevice').value!==device)return;$('alarmCommandStatus').textContent=data.items.filter(r=>r.command?.kind==='alarm').slice(-10).map(r=>`${r.command.commandId}: ${r.status} ${r.result||''}`).join(' · ')||'No alarm commands yet';}catch(error){$('alarmCommandStatus').textContent=error.message;}};
$('dashboardDevice').onchange=()=>{['phoneStats','phoneAlarmDetails','alarmCommandStatus','phoneHistory','phoneToday','phoneSleep'].forEach(id=>$(id).textContent='');loadPhoneDashboard();};$('dashboardRefresh').onclick=loadPhoneDashboard;

function renderPhoneSleep(sleep,notice){
 if(!sleep){$('phoneSleep').textContent='No sleep snapshot. Sync an updated phone first.';return;}
 const table=(title,records)=>'<h3>'+escapeHtml(title)+'</h3>'+(!records.length?'<p>No records in this snapshot.</p>':records.map(row=>'<div class="card">'+Object.entries(row).map(([key,value])=>'<p>'+escapeHtml(key.replace(/([A-Z])/g,' $1'))+': '+escapeHtml(value===null?'Not recorded':String(value))+'</p>').join('')+'</div>').join(''));
 $('phoneSleep').innerHTML='<p>'+escapeHtml(notice)+'</p><h3>Health Connect</h3><p>'+escapeHtml(sleep.health.availability)+' · permission '+(sleep.health.permissionGranted?'granted':'not granted')+' · refreshed '+escapeHtml(String(sleep.health.refreshedAtMillis))+'</p>'+(sleep.health.errorMessage?'<p>'+escapeHtml(sleep.health.errorMessage)+'</p>':'')+table('Sleep sessions',sleep.health.sessions)+table('Actigraphy',sleep.actigraphy)+table('Snore events (measurements, not audio)',sleep.snores)+table('Pre-sleep tags',sleep.tags)+table('Tag correlations',sleep.correlations)+table('Bedtime preferences',[sleep.bedtimePreferences])+table('Noise baseline',[sleep.noiseBaseline])+table('Sonar summary',[sleep.sonar])+'<p>Experimental estimates are not medical advice. Empty means no records in this snapshot, not no sleep.</p>';
}

function clearPhoneDashboard(){
 ['phoneStats','phoneAlarmDetails','alarmCommandStatus','phoneHistory','phoneToday','phoneSleep','dashboardStatus'].forEach(id=>$(id).textContent='');
 $('dashboardDevice').innerHTML='';
 todaySelectedCity=null;
}
$('logoutBtn').addEventListener('click',clearPhoneDashboard);
