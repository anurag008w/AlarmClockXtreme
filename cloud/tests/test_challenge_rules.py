import unittest,re
from pathlib import Path
import challenge_rules as rules
class ChallengeRulesTests(unittest.TestCase):
 def test_all_android_types_match(self):
  source=(Path(__file__).parents[2]/'app/src/main/java/com/sysadmindoc/alarmclock/data/model/Alarm.kt').read_text()
  raw=source.split('private val VALID_CHALLENGE_TYPES = setOf(',1)[1].split(')',1)[0]
  self.assertEqual(rules.TYPES,set(re.findall('"([A-Z_]+)"',raw)))
 def test_primary_and_chain_references(self):
  for kind,key in rules.REQUIRED.items():
   for payload in [{'challengeType':kind},{'challengeType':'NONE','challengeChain':kind}]:
    with self.assertRaises(rules.HTTPException):rules.validate(payload)
    rules.validate({**payload,key:'reference'})
 def test_unknown_type_not_silently_disabled(self):
  with self.assertRaises(rules.HTTPException):rules.validate({'challengeType':'typo'})
