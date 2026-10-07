import copy
import unittest
import test_settings
import dashboard
import server

def fixture():
 health={'enabled':False,'availability':'NOT_INCLUDED','permissionGranted':False,'refreshedAtMillis':0,'sessionsRead':0,'errorMessage':None,'sessions':[]}
 sleep={'health':health,'actigraphy':[],'snores':[],'tags':[],'correlations':[], 'bedtimePreferences':{'chronotypeAnswers':'','jetLagTargetWakeMinutes':480,'jetLagAdjustmentDays':4,'jetLagDirection':'auto'},'noiseBaseline':{'measuredAtMillis':0,'dbfs':None,'level':None},'sonar':{'active':False,'sessionStartedAt':0,'lastEndedAt':0,'lastTotalMinutes':0,'lastAwakeMinutes':0,'lastLightMinutes':0,'lastDeepMinutes':0,'lastSnoreEventCount':0,'lastSnorePeakDb':0.0}}
 stats={k:0 for k in ['totalDismissed','totalSnoozed','totalSkipped','totalMissed','averageDismissTimeSec','snoozeRate','currentStreak','bestStreak','nextStreakGoal','alarmsThisWeek']};stats.update(streakIncludesToday=False,dayOfWeekCounts={},dayOfWeekAvgResponseSec={})
 return {'phoneObservedMillis':1000,'timezone':'Asia/Kolkata','calendarDate':'2026-10-06','location':None,'calendarStatus':'permission_required','calendar':[],'nextAlarm':None,'stats':stats,'events':[],'sleep':sleep,'alarmDetails':[]}
class DashboardTests(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.fixture=test_settings.SettingsTests();await self.fixture.asyncSetUp();self.store=self.fixture.store;self.user=self.fixture.user
  from unittest.mock import patch
  self.guard=patch.object(dashboard,'require_private_data_repo',return_value=None);self.guard.start()
  self.store[('u','devices')]={'devices':{'phone-1':{'platform':'android'}}}
 async def asyncTearDown(self):self.guard.stop();await self.fixture.asyncTearDown()
 async def test_snapshot_read_device_and_account_isolation(self):
  await server.put_dashboard('phone-1',fixture(),self.user)
  self.assertIsNotNone((await server.get_dashboard('phone-1',self.user))['snapshot'])
  self.assertIsNone((await server.get_dashboard('phone-2',self.user))['snapshot'])
  self.assertIsNone((await server.get_dashboard('phone-1',{'id':'other'}))['snapshot'])
  with self.assertRaises(server.HTTPException):await server.put_dashboard('unknown',fixture(),self.user)
 def test_secrets_unknown_fields_never_accepted(self):
  for scope in ['root','sleep','health']:
   p=fixture();row=p if scope=='root' else p['sleep'] if scope=='sleep' else p['sleep']['health'];row['apiKey']='no'
   with self.assertRaises(server.HTTPException):dashboard.validate(p)
 def test_invalid_numeric_and_limits(self):
  for key,value in [('phoneObservedMillis',True),('timezone','bad-zone'),('calendarDate','not-a-date')]:
   p=fixture();p[key]=value
   with self.assertRaises(server.HTTPException):dashboard.validate(p)
  p=fixture();p['location']={'name':'x','latitude':float('nan'),'longitude':0,'manual':False,'kind':'saved_weather_location'}
  with self.assertRaises(server.HTTPException):dashboard.validate(p)
 def test_sleep_stage_records_are_validated(self):
  p=fixture();p['sleep']['health']['sessions']=[{k:10 for k in ['startMillis','endMillis','durationMinutes','asleepStageMinutes','lightStageMinutes','deepStageMinutes','remStageMinutes','awakeStageMinutes','unknownStageMinutes']}]
  self.assertEqual(len(dashboard.validate(p)['sleep']['health']['sessions']),1)
  p['sleep']['health']['sessions']*=51
  with self.assertRaises(server.HTTPException):dashboard.validate(p)

class MoshiWireCompatibilityTests(unittest.TestCase):
 def omit_nulls(self,value):
  if isinstance(value,dict):return {k:self.omit_nulls(v) for k,v in value.items() if v is not None}
  if isinstance(value,list):return [self.omit_nulls(v) for v in value]
  return value
 def test_mobile_default_null_omission_matches_explicit_null_snapshot(self):
  p=fixture()
  p['alarmDetails']=[{'cloudAlarmId':None,'label':'Alarm','nextTriggerTime':0,'canSkipNext':False,'lookbackDays':30,'fireCount':0,'avgSnoozesPerFire':0.0,'avgDismissTimeSec':0,'missedCount':0,'readiness':'NO_HARDWARE_REQUIRED','readinessMessage':None,'blocksSave':False}]
  p['sleep']['correlations']=[{'key':'x','label':'x','loggedNights':0,'nightsWithSessions':0,'averageRestlessMinutes':None,'baselineRestlessMinutes':None,'deltaRestlessMinutes':None}]
  self.assertEqual(dashboard.validate(self.omit_nulls(p)),dashboard.validate(p))
 def test_omitted_required_nonnullable_and_unknown_fields_still_fail(self):
  p=self.omit_nulls(fixture());del p['sleep']['health']['enabled']
  with self.assertRaises(server.HTTPException):dashboard.validate(p)
  p=self.omit_nulls(fixture());p['sleep']['noiseBaseline']['token']='bad'
  with self.assertRaises(server.HTTPException):dashboard.validate(p)

class PrivateStoreGuardTests(unittest.TestCase):
 def test_public_or_unverifiable_repo_blocks_sensitive_write(self):
  from unittest.mock import patch,MagicMock
  import urllib.request
  for content in ('{"private":false}','{}'):
   context=MagicMock();context.__enter__.return_value.read.return_value=content.encode()
   with patch.object(server.github_sync,'SYNC_ENABLED',True),patch.object(urllib.request,'urlopen',return_value=context):
    with self.assertRaises(server.HTTPException):dashboard.require_private_data_repo()
 def test_disabled_sync_blocks_sensitive_write(self):
  from unittest.mock import patch
  with patch.object(server.github_sync,'SYNC_ENABLED',False):
   with self.assertRaises(server.HTTPException):dashboard.require_private_data_repo()
