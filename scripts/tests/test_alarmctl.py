import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch
import argparse

spec=importlib.util.spec_from_file_location('alarmctl', Path(__file__).parents[1]/'alarmctl.py')
cli=importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)

class CliTests(unittest.TestCase):
    def test_timer_command_targets_device_and_reports_pending(self):
        args=cli.parser().parse_args(['timer-command','phone-1','start','--seconds','60','--command-id','command-123'])
        with patch.object(cli,'api',return_value={'status':'pending'}) as api:
            cli.cmd_timer_command(args)
        sent=api.call_args.kwargs['body']
        self.assertEqual(sent['deviceId'],'phone-1');self.assertEqual(sent['commandId'],'command-123')
        self.assertEqual(sent['payload']['seconds'],60)
    def test_timer_command_invalid_seconds(self):
        args=cli.parser().parse_args(['timer-command','phone-1','start','--seconds','0'])
        with self.assertRaises(SystemExit):cli.cmd_timer_command(args)
    def test_world_clocks_needs_phone_seed(self):
        with patch.object(cli,'api',return_value={'version':0,'payload':{}}):
            with self.assertRaises(SystemExit):cli.cmd_world_clocks()
    def test_world_clocks_reads_synced_zones(self):
        import io
        from contextlib import redirect_stdout
        out=io.StringIO()
        with patch.object(cli,'api',return_value={'version':1,'payload':{'worldClockZones':'Asia/Kolkata'}}),redirect_stdout(out):cli.cmd_world_clocks()
        self.assertIn('Asia/Kolkata',out.getvalue())
    def test_all_model_controls_listed(self):
        import re
        model=(Path(__file__).parents[2]/'app/src/main/java/com/sysadmindoc/alarmclock/data/model/Alarm.kt').read_text().split(') {',1)[0]
        keys=set(re.findall(r'val (\w+):',model))-{'id','createdAt','nextTriggerTime'}
        self.assertEqual(keys,set(cli.FIELD_TYPES))
    def test_string_references_do_not_become_numbers(self):
        p={};cli.apply_sets(p,['barcodeValue=00123','nfcTagId=00007','label=true','volume=70'])
        self.assertEqual(p,{'barcodeValue':'00123','nfcTagId':'00007','label':'true','volume':70})
    def test_invalid_time_rejected(self):
        for p in ({'hour':24},{'minute':-1},{'volume':True},{'repeatDays':['MOON']}):
            with self.assertRaises(SystemExit): cli.validate_payload(p)
    def test_unknown_field_rejected(self):
        with self.assertRaises(SystemExit):cli.validate_payload({'ringtoneUrl':'typo'})
    def test_device_fields_not_editable(self):
        with self.assertRaises(SystemExit):cli.validate_payload({'nextTriggerTime':123})
    def test_conflicting_readback_not_success(self):
        with patch.object(cli,'list_alarms',return_value=[{'id':'a','version':3,'payload':{}}]):
            with self.assertRaises(SystemExit):cli.verify_commit({'id':'a','version':2,'payload':{}})
    def test_missing_readback_not_success(self):
        with patch.object(cli,'list_alarms',return_value=[]):
            with self.assertRaises(SystemExit):cli.verify_commit({'id':'a','version':2})
    def test_delete_readback(self):
        with patch.object(cli,'list_alarms',return_value=[{'id':'a','version':2,'deletedAt':'now','payload':{}}]):
            cli.verify_commit({'id':'a','version':2},deleted=True)
    def test_sound_clears_competing_sources(self):
        args=cli.parser().parse_args(['sound','a','--uri','content://sound/77'])
        with patch.object(cli,'cmd_update') as update:
            cli.cmd_sound(args)
        self.assertIn('ringtoneUri=content://sound/77',args.set_values)
        self.assertIn('ringtonePool=',args.set_values)
        self.assertIn('internetRadioUrl=',args.set_values)
    def test_radio_requires_https(self):
        args=cli.parser().parse_args(['sound','a','--radio','http://bad'])
        with self.assertRaises(SystemExit):cli.cmd_sound(args)
    def test_update_keeps_other_fields(self):
        original={'hour':7,'minute':0,'label':'Wake','ringtoneUri':'content://polozhenie','volume':100}
        current={'id':'a','version':1,'payload':original}
        args=cli.parser().parse_args(['update','a','--set','volume=75'])
        with patch.object(cli,'find_alarm',return_value=current),patch.object(cli,'api',return_value={'id':'a','version':2,'payload':original}) as api,patch.object(cli,'verify_commit'):
            cli.cmd_update(args)
        sent=api.call_args.kwargs['body']['payload']
        self.assertEqual(sent['ringtoneUri'],original['ringtoneUri']);self.assertEqual(sent['volume'],75)
        self.assertEqual(api.call_args.kwargs['body']['expectedVersion'],1)

if __name__=='__main__':unittest.main()

class SettingsCliTests(unittest.TestCase):
    def test_settings_require_phone_bootstrap(self):
        args=cli.parser().parse_args(['settings-update','--set','bedtimeHour=22'])
        with patch.object(cli,'api',return_value={'version':0,'payload':{}}):
            with self.assertRaises(SystemExit):cli.cmd_settings_update(args)
    def test_settings_update_preserves_other_preferences(self):
        args=cli.parser().parse_args(['settings-update','--set','bedtimeHour=22'])
        payload={'bedtimeHour':22,'customTypingPhrases':'my phrase'}
        with patch.object(cli,'api',side_effect=[{'version':1,'payload':{'bedtimeHour':23,'customTypingPhrases':'my phrase'}},{'version':2,'payload':payload},{'version':2,'payload':payload}]) as api:
            cli.cmd_settings_update(args)
        self.assertEqual(api.call_args_list[1].kwargs['body']['payload'],payload)
    def test_settings_secret_rejected(self):
        args=cli.parser().parse_args(['settings-update','--set','hueApiKey=secret'])
        with patch.object(cli,'api',return_value={'version':1,'payload':{}}):
            with self.assertRaises(SystemExit):cli.cmd_settings_update(args)

class SectionCliTests(unittest.TestCase):
 def test_news_query_is_encoded(self):
  with patch('sys.argv',['alarmctl','news','--feed','bbc']),patch.object(cli,'api',return_value={'items':[]}) as api:
   cli.main()
  self.assertEqual(api.call_args.args,('GET','api/news?feed=bbc'))
 def test_weather_query_has_explicit_coordinates(self):
  with patch('sys.argv',['alarmctl','weather','18.5','73.8','--unit','celsius']),patch.object(cli,'api',return_value={}) as api:
   cli.main()
  self.assertEqual(api.call_args.args,('GET','api/today/weather?latitude=18.5&longitude=73.8&unit=celsius'))
 def test_bedtime_excludes_non_bedtime_data(self):
  import io
  from contextlib import redirect_stdout
  out=io.StringIO()
  with patch('sys.argv',['alarmctl','bedtime']),patch.object(cli,'api',return_value={'payload':{'bedtimeHour':23,'sleepGoalHours':8,'webhookUrl':'private'}}),redirect_stdout(out):cli.main()
  self.assertIn('bedtimeHour',out.getvalue());self.assertNotIn('webhookUrl',out.getvalue())

class ChallengeCliTests(unittest.TestCase):
 def test_reference_required_for_active_and_chain(self):
  for payload in [{'challengeType':'NFC_SCAN'},{'challengeType':'NONE','challengeChain':'PHOTO_MATCH'}]:
   with self.assertRaises(SystemExit):cli.validate_payload(payload)
  cli.validate_payload({'challengeType':'NFC_SCAN','nfcTagId':'00007'})

class BatchCliTests(unittest.TestCase):
 def test_batch_requires_review_flag(self):
  with self.assertRaises(SystemExit):cli.cmd_batch(cli.parser().parse_args(['batch','disable','a']))
 def test_batch_stops_on_conflict(self):
  args=cli.parser().parse_args(['batch','disable','a','b','--confirm'])
  rows=[{'id':id,'version':1,'payload':{'hour':7,'isEnabled':True}} for id in ['a','b']]
  with patch.object(cli,'list_alarms',return_value=rows),patch.object(cli,'api',side_effect=[{'id':'a'},SystemExit('conflict')]) as api,patch.object(cli,'verify_commit'):
   with self.assertRaises(SystemExit) as error:cli.cmd_batch(args)
   self.assertIn('1 verified',str(error.exception));self.assertEqual(api.call_count,2)
 def test_missing_batch_id_writes_nothing(self):
  with patch.object(cli,'list_alarms',return_value=[]),patch.object(cli,'api') as api:
   with self.assertRaises(SystemExit):cli.cmd_batch(cli.parser().parse_args(['batch','delete','missing','--confirm']))
   api.assert_not_called()

class TemplateCliTests(unittest.TestCase):
 def test_template_is_disabled_no_write(self):
  import io
  from contextlib import redirect_stdout
  out=io.StringIO()
  with patch.object(cli,'api') as api,redirect_stdout(out):cli.cmd_template(cli.parser().parse_args(['template','work_alarm']))
  self.assertFalse(__import__('json').loads(out.getvalue())['isEnabled']);api.assert_not_called()

class SkipCliTests(unittest.TestCase):
 def test_skip_review_no_write_then_exact_phone_occurrence(self):
  data={'snapshot':{'payload':{'alarmDetails':[{'cloudAlarmId':'a','canSkipNext':True,'nextTriggerTime':123456}]}}}
  args=cli.parser().parse_args(['skip-next','phone-1','a'])
  with patch.object(cli,'api',return_value=data) as api:
   with self.assertRaises(SystemExit):cli.cmd_skip_next(args)
   self.assertEqual(api.call_count,1)
  args.confirm=True;args.command_id='skip-12345'
  with patch.object(cli,'api',side_effect=[data,{'status':'pending'}]) as api:cli.cmd_skip_next(args)
  self.assertEqual(api.call_args.kwargs['body']['payload'],{'cloudAlarmId':'a','expectedNextTriggerTime':123456})
