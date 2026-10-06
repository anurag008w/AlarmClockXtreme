import os,copy,unittest
from unittest.mock import patch,AsyncMock
os.environ.setdefault('JWT_SECRET','test-secret');os.environ.setdefault('GH_TOKEN','test-token');os.environ['GITHUB_SYNC_ENABLED']='false'
import server
class SettingsTests(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.store={}
  async def get(uid,scope):return copy.deepcopy(self.store.get((uid,scope),{}))
  async def save(uid,scope,value):self.store[(uid,scope)]=copy.deepcopy(value);return value
  self.p=patch.multiple(server.usersync,get_scope=AsyncMock(side_effect=get),save_scope=AsyncMock(side_effect=save));self.p.start()
  self.g=patch.multiple(server.github_sync,pull_data=lambda:True,push_data=lambda:True,ensure_current=lambda x:True);self.g.start()
  self.user={'id':'u','email':'test@example.invalid'}
 async def asyncTearDown(self):self.p.stop();self.g.stop()
 async def test_save_read_conflict(self):
  saved=await server.put_settings(server.AlarmWrite(payload={'bedtimeHour':22,'challengeBypassEnabled':True}),self.user)
  self.assertEqual(saved['version'],1)
  read=await server.get_settings(self.user);self.assertEqual(read['payload']['bedtimeHour'],22)
  with self.assertRaises(server.HTTPException) as ctx:await server.put_settings(server.AlarmWrite(payload={'bedtimeHour':21}),self.user)
  self.assertEqual(ctx.exception.status_code,409)
 async def test_secrets_never_accepted(self):
  for key in ('hueApiKey','webhookSigningSecret','googleRoutesApiKey','healthConnectEnabled','lastKnownLatitude'):
   with self.assertRaises(server.HTTPException):await server.put_settings(server.AlarmWrite(payload={key:'no'}),self.user)
 async def test_types(self):
  for payload in ({'bedtimeHour':True},{'challengeBypassEnabled':'yes'},{'customTypingPhrases':None}):
   with self.assertRaises(server.HTTPException):await server.put_settings(server.AlarmWrite(payload=payload),self.user)
 async def test_out_of_range_rejected(self):
  for payload in ({'bedtimeHour':24},{'sleepGoalHours':20},{'challengeAudioDuckPercent':100},{'vacationModeEnabled':True,'vacationStartMillis':10,'vacationEndMillis':5}):
   with self.assertRaises(server.HTTPException):await server.put_settings(server.AlarmWrite(payload=payload),self.user)
 async def test_world_clock_zones(self):
  saved=await server.put_settings(server.AlarmWrite(payload={'worldClockZones':'Asia/Kolkata|Europe/London'}),self.user)
  self.assertEqual(saved['payload']['worldClockZones'],'Asia/Kolkata|Europe/London')
  for raw in ('Not/A_Zone','Europe/London|Europe/London','|'):
   with self.assertRaises(server.HTTPException):await server.put_settings(server.AlarmWrite(payload={'worldClockZones':raw},expectedVersion=1),self.user)
  saved=await server.put_settings(server.AlarmWrite(payload={'worldClockZones':''},expectedVersion=1),self.user)
  self.assertEqual(saved['payload']['worldClockZones'],'')
 async def test_isolation(self):
  await server.put_settings(server.AlarmWrite(payload={'bedtimeHour':22}),self.user)
  read=await server.get_settings({'id':'other','email':'other@example.invalid'})
  self.assertEqual(read['version'],0)
 async def test_merge_settings_uses_versioned_rows(self):
  from pathlib import Path
  self.assertTrue(server.github_sync._is_alarm_scope(Path('sync/u/settings.json')))

class SettingsMergeTests(unittest.TestCase):
 def test_android_export_apply_and_cli_match_allowlist(self):
  import json,re
  from pathlib import Path
  root=Path(__file__).parents[2]
  fields=json.loads((root/'cloud/settings-fields.json').read_text())
  self.assertEqual(fields,json.loads((root/'scripts/settings-fields.json').read_text()))
  source=(root/'app/src/main/java/com/sysadmindoc/alarmclock/data/preferences/PreferencesManager.kt').read_text()
  export=source.split('suspend fun cloudSettings()',1)[1].split('suspend fun applyCloudSettings',1)[0]
  apply=source.split('suspend fun applyCloudSettings',1)[1].split('suspend fun getCurrentSettings',1)[0]
  self.assertEqual(set(fields),set(re.findall(r'"(\w+)" to current\.',export)))
  self.assertEqual(set(fields),set(re.findall(r'payload\["(\w+)"\]',apply)))

 def test_newer_settings_row_survives_merge(self):
  local={'items':{'preferences':{'id':'preferences','payload':{'bedtimeHour':23},'version':1,'updated_at':'a'}}}
  remote={'items':{'preferences':{'id':'preferences','payload':{'bedtimeHour':22},'version':2,'updated_at':'b'}}}
  merged=server.github_sync._merge_alarm_scope(local,remote)
  self.assertEqual(merged['items']['preferences']['payload']['bedtimeHour'],22)
 def test_same_version_conflict_does_not_silently_choose_stale_local(self):
  local={'items':{'preferences':{'id':'preferences','payload':{'bedtimeHour':23},'version':2,'updated_at':'a'}}}
  remote={'items':{'preferences':{'id':'preferences','payload':{'bedtimeHour':22},'version':2,'updated_at':'b'}}}
  merged=server.github_sync._merge_alarm_scope(local,remote)
  self.assertEqual(merged['items']['preferences']['payload']['bedtimeHour'],22)
