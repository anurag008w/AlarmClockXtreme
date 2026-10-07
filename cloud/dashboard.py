"""Validate phone-owned display snapshots. Strict schema includes approved sleep measurements but excludes secrets and raw media."""
import math
from zoneinfo import ZoneInfo
from fastapi import HTTPException

def fail():raise HTTPException(400,'invalid_dashboard_snapshot')
def text(value,limit=500):
 if not isinstance(value,str) or len(value)>limit:fail()
 return value
def integer(value):
 if type(value) is not int or not 0<=value<=9007199254740991:fail()
 return value
def boolean(value):
 if type(value) is not bool:fail()
 return value
def clean_row(row,schema):
 if not isinstance(row,dict) or set(row)!=set(schema):fail()
 return {key:fn(row[key]) for key,fn in schema.items()}
def normalize_nullable_fields(body):
 # Moshi's default Map adapter omits null values. Restore only schema-defined
 # nullable fields; do not broaden accepted keys, types or numeric ranges.
 if not isinstance(body,dict):return body
 body=dict(body)
 for key in ('location','nextAlarm'):body.setdefault(key,None)
 details=body.get('alarmDetails')
 if isinstance(details,list):
  body['alarmDetails']=[dict(row,cloudAlarmId=row.get('cloudAlarmId'),readinessMessage=row.get('readinessMessage')) if isinstance(row,dict) else row for row in details]
 sleep=body.get('sleep')
 if isinstance(sleep,dict):
  sleep=dict(sleep);body['sleep']=sleep
  if isinstance(sleep.get('health'),dict):
   sleep['health']=dict(sleep['health']);sleep['health'].setdefault('errorMessage',None)
  if isinstance(sleep.get('noiseBaseline'),dict):
   sleep['noiseBaseline']=dict(sleep['noiseBaseline'])
   for key in ('dbfs','level'):sleep['noiseBaseline'].setdefault(key,None)
  if isinstance(sleep.get('correlations'),list):
   sleep['correlations']=[dict(row,**{key:row.get(key) for key in ('averageRestlessMinutes','baselineRestlessMinutes','deltaRestlessMinutes')}) if isinstance(row,dict) else row for row in sleep['correlations']]
 return body

def validate(body):
 body=normalize_nullable_fields(body)
 keys={'phoneObservedMillis','timezone','calendarDate','location','calendarStatus','calendar','nextAlarm','stats','events','sleep','alarmDetails'}
 if not isinstance(body,dict) or not keys-{'alarmDetails'} <= set(body) or set(body)-keys:fail()
 result={'phoneObservedMillis':integer(body['phoneObservedMillis']),'timezone':text(body['timezone'],100),'calendarDate':text(body['calendarDate'],10)}
 try:ZoneInfo(result['timezone'])
 except (ValueError,KeyError):fail()
 import datetime
 try:datetime.date.fromisoformat(result['calendarDate'])
 except ValueError:fail()
 status=body['calendarStatus']
 if status not in {'disabled','permission_required','unavailable','available'}:fail()
 result['calendarStatus']=status
 location=body['location']
 if location is not None:
  def coordinate(value):
   if type(value) not in {float,int} or not math.isfinite(value):fail()
   return value
  location=clean_row(location,{'name':text,'latitude':coordinate,'longitude':coordinate,'manual':boolean,'kind':text})
  if not -90<=location['latitude']<=90 or not -180<=location['longitude']<=180 or location['kind']!='saved_weather_location':fail()
 result['location']=location
 result['nextAlarm']=None if body['nextAlarm'] is None else clean_row(body['nextAlarm'],{'label':text,'nextTriggerTime':integer})
 stats_schema={key:integer for key in ['totalDismissed','totalSnoozed','totalSkipped','totalMissed','averageDismissTimeSec','snoozeRate','currentStreak','bestStreak','nextStreakGoal','alarmsThisWeek']}
 def weekday_map(value):
  if not isinstance(value,dict) or any(key not in {str(i) for i in range(1,8)} for key in value):fail()
  return {key:integer(v) for key,v in value.items()}
 stats_schema.update(streakIncludesToday=boolean,dayOfWeekCounts=weekday_map,dayOfWeekAvgResponseSec=weekday_map)
 result['stats']=clean_row(body['stats'],stats_schema)
 if result['stats']['snoozeRate']>100:fail()
 for key,limit,schema in [('calendar',100,{'id':integer,'title':text,'startTime':integer,'endTime':integer,'allDay':boolean,'location':text}),('events',50,{'id':integer,'alarmLabel':text,'scheduledTime':integer,'firedAt':integer,'action':text,'actionAt':integer,'challengeType':text,'challengeSolveTimeMs':integer,'challengeRetryCount':integer,'snoozeCount':integer})]:
  if not isinstance(body[key],list) or len(body[key])>limit:fail()
  result[key]=[clean_row(row,schema) for row in body[key]]
 if any(row['action'] not in {'DISMISSED','SNOOZED','SKIPPED','MISSED'} for row in result['events']):fail()
 if any(row['endTime']<row['startTime'] for row in result['calendar']):fail()
 if status!='available' and result['calendar']:fail()
 result['alarmDetails']=rows(body.get('alarmDetails',[]),100,{'cloudAlarmId':nullable(text),'label':text,'nextTriggerTime':integer,'canSkipNext':boolean,'lookbackDays':integer,'fireCount':integer,'avgSnoozesPerFire':number,'avgDismissTimeSec':integer,'missedCount':integer,'readiness':text,'readinessMessage':nullable(text),'blocksSave':boolean})
 if any(row['readiness'] not in {'READY','NEEDS_PERMISSION','NEEDS_HARDWARE','NEEDS_REFERENCE','NO_HARDWARE_REQUIRED'} for row in result['alarmDetails']):fail()
 result['sleep']=validate_sleep(body['sleep'])
 return result

def nullable(fn):return lambda value: None if value is None else fn(value)
def signed(value):
 if type(value) is not int or not -9007199254740991<=value<=9007199254740991:fail()
 return value
def number(value):
 if type(value) not in {float,int} or not math.isfinite(value) or abs(value)>1e12:fail()
 return value
def rows(value,limit,schema):
 if not isinstance(value,list) or len(value)>limit:fail()
 return [clean_row(row,schema) for row in value]
def validate_sleep(value):
 if not isinstance(value,dict) or set(value)!={'health','actigraphy','snores','tags','correlations','bedtimePreferences','noiseBaseline','sonar'}:fail()
 health=clean_row(value['health'],{'enabled':boolean,'availability':text,'permissionGranted':boolean,'refreshedAtMillis':integer,'sessionsRead':integer,'errorMessage':nullable(text),'sessions':lambda v:rows(v,50,{k:integer for k in ['startMillis','endMillis','durationMinutes','asleepStageMinutes','lightStageMinutes','deepStageMinutes','remStageMinutes','awakeStageMinutes','unknownStageMinutes']})})
 if health['availability'] not in {'AVAILABLE','PROVIDER_UPDATE_REQUIRED','UNAVAILABLE','NOT_INCLUDED'}:fail()
 act_schema={k:integer for k in ['id','alarmId','startedAt','endedAt','targetTime','totalMinutes','awakeMinutes','lightMinutes','deepMinutes','observedMinutesBeforeDecision']}
 act_schema.update(averageSleepIndex=number,firedEarly=boolean,algorithm=text,decisionReason=text,smartWakeMode=text)
 snore_schema={k:integer for k in ['id','sessionStartedAt','startedAt','endedAt','durationMillis','windowCount']};snore_schema.update(peakDb=number,averageDb=number,source=text)
 correlation_schema={k:nullable(signed) for k in ['averageRestlessMinutes','baselineRestlessMinutes','deltaRestlessMinutes']};correlation_schema.update(key=text,label=text,loggedNights=integer,nightsWithSessions=integer)
 result={'health':health,'actigraphy':rows(value['actigraphy'],10,act_schema),'snores':rows(value['snores'],50,snore_schema),'tags':rows(value['tags'],20,{'localDate':text,'tagKey':text,'loggedAt':integer}),'correlations':rows(value['correlations'],20,correlation_schema),
 'bedtimePreferences':clean_row(value['bedtimePreferences'],{'chronotypeAnswers':text,'jetLagTargetWakeMinutes':integer,'jetLagAdjustmentDays':integer,'jetLagDirection':text}),
 'noiseBaseline':clean_row(value['noiseBaseline'],{'measuredAtMillis':integer,'dbfs':nullable(number),'level':nullable(text)}),
 'sonar':clean_row(value['sonar'],{'active':boolean,'sessionStartedAt':integer,'lastEndedAt':integer,'lastTotalMinutes':integer,'lastAwakeMinutes':integer,'lastLightMinutes':integer,'lastDeepMinutes':integer,'lastSnoreEventCount':integer,'lastSnorePeakDb':number})}
 if any(row['endMillis']<row['startMillis'] for row in health['sessions']):fail()
 if any(row['endedAt']<row['startedAt'] for row in result['actigraphy']+result['snores']):fail()
 return result

def require_private_data_repo():
 import json
 from urllib.request import Request,urlopen
 import github_sync
 if not github_sync.SYNC_ENABLED:raise HTTPException(503,'private_data_sync_required')
 try:
  request=Request('https://api.github.com/repos/'+github_sync.DATA_REPO,headers={'Authorization':'Bearer '+github_sync.GH_TOKEN,'Accept':'application/vnd.github+json','User-Agent':'AlarmClockXtreme'})
  with urlopen(request,timeout=10) as response:data=json.load(response)
  if data.get('private') is not True:raise ValueError('not_private')
 except Exception:raise HTTPException(503,'private_data_repo_unverified')
