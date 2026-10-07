let utilityBusy = false;
async function refreshPhoneTimers() {
  const token=state.token;const device=$('utilityDevice').value;
  if(!device) { $('phoneUtilityStatus').textContent='Choose a phone first.';return; }
  const data=await api('/api/utilities/'+encodeURIComponent(device));
  if(state.token!==token || $('utilityDevice').value!==device)return;
  const snapshot=data.items.find(row=>row.id==='snapshot-'+device);
  const commands=data.items.filter(row=>row.command).sort((a,b)=>a.createdMillis-b.createdMillis);
  $('phoneUtilityStatus').textContent=commands.slice(-5).map(row=>`${row.command.action}: ${row.status}${row.result ? ' ('+row.result+')' : ''}`).join(' · ') || 'No commands yet.';
  const payload=snapshot?.payload;
  const stopwatch=payload?.stopwatch;
  $('phoneStopwatchDisplay').textContent=stopwatch ? `${stopwatch.state} · ${(stopwatch.elapsedMillis/1000).toFixed(2)}s at snapshot · ${stopwatch.laps.length} laps` : 'No phone snapshot loaded';
  const age=payload ? Math.max(0,Math.floor((data.serverNowMillis-payload.observedServerMillis)/1000)) : null;
  $('phoneTimerList').innerHTML=payload ? `<p>Phone snapshot ${age}s old. Countdown is approximate, not proof of ringing.</p>`+payload.timers.map(timer=>`<div class="card"><strong>${escapeHtml(timer.label||'Timer '+timer.id)}</strong><p>${escapeHtml(timer.state)} · ${Math.ceil(timer.remainingMillis/1000)}s at snapshot</p>${['pause','resume','stop'].map(action=>`<button class="btn secondary" type="button" data-native-timer="${timer.id}" data-action="${action}">${action}</button>`).join('')}</div>`).join('') : 'No phone snapshot. Sync an updated Android app first.';
  $('phoneTimerList').querySelectorAll('[data-native-timer]').forEach(button=>button.onclick=()=>sendPhoneTimer(button.dataset.action,{timerId:Number(button.dataset.nativeTimer)}));
}
async function sendPhoneTimer(action,payload) {
  if(utilityBusy)return;
  const device=$('utilityDevice').value;if(!device)return;
  const commandId='v2-'+Date.now()+'-'+crypto.randomUUID().replaceAll('-','');utilityBusy=true;
  try {
    const row=await api('/api/utilities/commands',{method:'POST',body:JSON.stringify({deviceId:device,commandId,kind:'timer',action,payload})});
    $('phoneUtilityStatus').textContent=`${row.status}: command ${commandId}. Not proof the phone applied it.`;
    await refreshPhoneTimers();
  } catch(error) { $('phoneUtilityStatus').textContent=`${error.message}. Command ID ${commandId}: check phone status before retrying.`; }
  finally {utilityBusy=false;}
}
$('loadUtilityDevices').onclick=async()=>{
 const token=state.token;try {const data=await api('/api/utilities/devices');if(state.token!==token)return;$('utilityDevice').innerHTML='<option value="">Choose phone</option>'+data.devices.filter(d=>d.platform==='android').map(d=>`<option value="${escapeAttr(d.id)}">${escapeHtml(d.id)} (${escapeHtml(d.appVersion||'unknown version')})</option>`).join('');}
 catch(error){$('phoneUtilityStatus').textContent=error.message;}
};
$('utilityDevice').onchange=()=>refreshPhoneTimers().catch(error=>$('phoneUtilityStatus').textContent=error.message);
$('refreshPhoneTimers').onclick=()=>refreshPhoneTimers().catch(error=>$('phoneUtilityStatus').textContent=error.message);
$('startPhoneTimer').onclick=()=>{
 const seconds=Number($('phoneTimerSeconds').value);
 if(!Number.isInteger(seconds)||seconds<1||seconds>86400){$('phoneUtilityStatus').textContent='Seconds must be 1..86400';return;}
 sendPhoneTimer('start',{seconds,label:$('phoneTimerLabel').value});
};

document.querySelectorAll('[data-phone-stopwatch]').forEach(button=>button.onclick=async()=>{
 if(utilityBusy)return;
 const device=$('utilityDevice').value;
 if(!device){$('phoneStopwatchStatus').textContent='Select a phone in Timer first.';return;}
 if(button.dataset.phoneStopwatch==='reset' && !confirm('Reset stopwatch and remove all laps on this phone?'))return;
 utilityBusy=true;const commandId='v2-'+Date.now()+'-'+crypto.randomUUID().replaceAll('-','');
 try {
  const row=await api('/api/utilities/commands',{method:'POST',body:JSON.stringify({deviceId:device,commandId,kind:'stopwatch',action:button.dataset.phoneStopwatch,payload:{}})});
  $('phoneStopwatchStatus').textContent=`${row.status}: ${commandId}. Read phone status for acknowledgement.`;
  await refreshPhoneTimers();
 } catch(error){$('phoneStopwatchStatus').textContent=`${error.message}. Command ${commandId}: check before retrying.`;}
 finally{utilityBusy=false;}
});

setInterval(()=>{ if(state.token && !document.hidden && $('utilityDevice').value && (document.querySelector('[data-tab=timer]').classList.contains('active') || document.querySelector('[data-tab=stopwatch]').classList.contains('active'))) refreshPhoneTimers().catch(error=>$('phoneUtilityStatus').textContent=error.message); },15000);

let autoPhoneToken = "";
let autoPhoneRequest = null;
async function ensureAccountPhone() {
 if (!state.token) return;
 if (autoPhoneToken===state.token && autoPhoneRequest) return autoPhoneRequest;
 const token=state.token;
 autoPhoneToken=token;
 autoPhoneRequest=(async()=>{
  const data=await api('/api/utilities/devices');
  if(state.token!==token)return;
  const devices=data.devices.filter(d=>d.platform==='android');
  for(const id of ['utilityDevice','dashboardDevice']) {
   const node=$(id), previous=node.value;
   node.innerHTML='<option value="">Choose phone</option>'+devices.map(d=>`<option value="${escapeAttr(d.id)}">${escapeHtml(d.id)} (${escapeHtml(d.appVersion||'unknown')})</option>`).join('');
   node.value=devices.length===1?devices[0].id:devices.some(d=>d.id===previous)?previous:'';
   node.hidden=devices.length===1;
  }
  $('loadUtilityDevices').hidden=devices.length===1;
  $('dashboardPhones').hidden=devices.length===1;
  if(devices.length===1) {
   await Promise.all([refreshPhoneTimers(),loadPhoneDashboard()]);
  }
 })().catch(e=>{autoPhoneToken='';autoPhoneRequest=null;throw e;});
 return autoPhoneRequest;
}
for(const tab of document.querySelectorAll('[data-tab]')) tab.addEventListener('click',()=>{
 ensureAccountPhone().then(()=>{
  if(['timer','stopwatch'].includes(tab.dataset.tab) && $('utilityDevice').value) return refreshPhoneTimers();
  if(['stats','bedtime','today'].includes(tab.dataset.tab) && $('dashboardDevice').value) return loadPhoneDashboard();
 }).catch(e=>{$('phoneUtilityStatus').textContent=e.message;});
});
if(state.token)ensureAccountPhone().catch(e=>{$('phoneUtilityStatus').textContent=e.message;});
