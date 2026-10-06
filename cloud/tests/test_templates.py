import json,re,unittest
from pathlib import Path
class TemplateTests(unittest.TestCase):
 def test_presets_match_android(self):
  root=Path(__file__).parents[2];rows=json.loads((root/'cloud/alarm-templates.json').read_text())
  self.assertEqual(rows,json.loads((root/'scripts/alarm-templates.json').read_text()))
  source=(root/'app/src/main/java/com/sysadmindoc/alarmclock/ui/templates/AlarmTemplate.kt').read_text()
  parts=source.split('AlarmTemplate(')[2:]
  for row in rows:
   part=next(p for p in parts if 'key = "'+row['key']+'"' in p)
   for key in ['hour','minute']:
    self.assertEqual(row['payload'][key],int(re.search(key+r' = (\d+)',part).group(1)))
   for key,default in [('gradualVolumeSeconds',60),('vibrationIntensity',2),('snoozeDurationMinutes',10)]:
    match=re.search(key+r' = (\d+)',part);self.assertEqual(row['payload'][key],int(match.group(1)) if match else default)
   match=re.search('challengeType = "([A-Z_]+)"',part);self.assertEqual(row['payload']['challengeType'],match.group(1) if match else 'NONE')

class PracticeDataTests(unittest.TestCase):
 def test_native_practice_lists_are_exact(self):
  import json,re
  from pathlib import Path
  root=Path(__file__).resolve().parents[2]
  js=(root/'cloud/public/practice-data.js').read_text()
  data=json.loads(js.split(' = ',1)[1].rstrip(';\n'))
  native=(root/'app/src/main/java/com/sysadmindoc/alarmclock/ui/alarmfiring/challenges/ChallengeGenerator.kt').read_text()
  for key,name in [('typing','TYPING_PHRASES'),('typingSpeed','TYPING_SPEED_PHRASES'),('wordle','WORDLE_WORDS')]:
   block=native.split(name+' = listOf(',1)[1].split('\n)',1)[0]
   self.assertEqual(data[key],re.findall(r'"([^\"]+)"',block))
  self.assertEqual(len(data['chess']),3);self.assertTrue(all(len(p['board'])==64 for p in data['chess']))
