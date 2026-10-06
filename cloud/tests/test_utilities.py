import test_settings
import unittest
from unittest.mock import patch
import utility_control
import server
class UtilityTests(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.fixture=test_settings.SettingsTests()
  await self.fixture.asyncSetUp()
  self.store=self.fixture.store;self.user=self.fixture.user
  self.store[('u','devices')]={'devices':{'phone-1':{'platform':'android'}}}
 async def asyncTearDown(self):await self.fixture.asyncTearDown()
 async def test_target_and_idempotency(self):
  body={'deviceId':'phone-1','commandId':'command-123','kind':'timer','action':'start','payload':{'seconds':60}}
  a=await server.utility_command(body,self.user)
  b=await server.utility_command(body,self.user)
  self.assertEqual(a,b);self.assertEqual(a['status'],'pending')
  body['payload']['seconds']=61
  with self.assertRaises(server.HTTPException):await server.utility_command(body,self.user)
 async def test_ack_is_owner_scoped_and_final(self):
  body={'deviceId':'phone-1','commandId':'command-123','kind':'timer','action':'start','payload':{'seconds':60}}
  await server.utility_command(body,self.user)
  with self.assertRaises(server.HTTPException):await server.utility_ack('phone-2',{'commandId':'command-123','status':'applied'},self.user)
  applied=await server.utility_ack('phone-1',{'commandId':'command-123','status':'applied'},self.user)
  self.assertEqual(applied['status'],'applied')
  again=await server.utility_ack('phone-1',{'commandId':'command-123','status':'rejected'},self.user)
  self.assertEqual(again['status'],'applied')
 async def test_invalid_and_unregistered(self):
  body={'deviceId':'other-phone','commandId':'command-123','kind':'timer','action':'start','payload':{'seconds':60}}
  with self.assertRaises(server.HTTPException):await server.utility_command(body,self.user)
  for seconds in (0,True,86401):
   body['payload']['seconds']=seconds
   with self.assertRaises(server.HTTPException):utility_control.validate(body)
 async def test_snapshot_uses_server_clock_not_phone_deadline(self):
  row=await server.utility_snapshot('phone-1',{'timers':[{'id':1,'state':'RUNNING','remainingMillis':60000,'totalSeconds':60,'endElapsedRealtime':999}]},self.user)
  self.assertNotIn('endElapsedRealtime',row['payload']['timers'][0])
  self.assertGreater(row['payload']['observedServerMillis'],0)
 async def test_stopwatch_snapshot_and_commands(self):
  body={'deviceId':'phone-1','commandId':'stopwatch-123','kind':'stopwatch','action':'lap','payload':{}}
  row=await server.utility_command(body,self.user)
  self.assertEqual(row['status'],'pending')
  snapshot=await server.utility_snapshot('phone-1',{'timers':[],'stopwatch':{'state':'RUNNING','elapsedMillis':1200,'laps':[{'number':1,'splitMillis':1200,'totalMillis':1200}]}},self.user)
  self.assertEqual(snapshot['payload']['stopwatch']['elapsedMillis'],1200)
  with self.assertRaises(server.HTTPException):await server.utility_snapshot('phone-1',{'timers':[],'stopwatch':{'state':'BAD'}},self.user)

class SkipCommandTests(unittest.TestCase):
 def test_exact_phone_occurrence_required(self):
  body={'deviceId':'phone-1','commandId':'skip-12345','kind':'alarm','action':'skip-next','payload':{'cloudAlarmId':'a','expectedNextTriggerTime':123456}}
  self.assertEqual(utility_control.validate(body)['payload']['expectedNextTriggerTime'],123456)
  for trigger in [True,0,-1,'123',float('nan')]:
   body['payload']['expectedNextTriggerTime']=trigger
   with self.assertRaises(server.HTTPException):utility_control.validate(body)

class RetentionTests(unittest.IsolatedAsyncioTestCase):
 async def test_versioned_retention_never_replays_evicted_id(self):
  fixture=test_settings.SettingsTests();await fixture.asyncSetUp()
  try:
   fixture.store[('u','devices')]={'devices':{'phone-1':{'platform':'android'}}}
   now=1900000000000;old='v2-'+str(now-86400001)+'-'+'a'*32;legacy='legacy-123'
   fixture.store[('u','utilities')]={'items':{'command-'+old:{'command':{'commandId':old}},'command-'+legacy:{'command':{'commandId':legacy}}}}
   body={'deviceId':'phone-1','commandId':'v2-'+str(now)+'-'+'b'*32,'kind':'timer','action':'start','payload':{'seconds':60}}
   with patch.object(utility_control.time,'time',return_value=now/1000):
    await server.utility_command(body,fixture.user)
    items=fixture.store[('u','utilities')]['items'];self.assertNotIn('command-'+old,items);self.assertIn('command-'+legacy,items)
    body['commandId']=old
    with self.assertRaises(server.HTTPException):await server.utility_command(body,fixture.user)
  finally:await fixture.asyncTearDown()
