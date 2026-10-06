"""Phone-owned utility command validation. Never share monotonic deadlines."""
import re
import time
from fastapi import HTTPException

ACTIONS = {'alarm': {'skip-next'}, 'timer': {'start', 'pause', 'resume', 'stop'}, 'stopwatch': {'start', 'pause', 'resume', 'reset', 'lap'}}

def validate(body):
    device = body.get('deviceId')
    key = body.get('commandId')
    if isinstance(key,str) and key.startswith('v2-'):
        match=re.fullmatch(r'v2-(\d{13})-([a-f0-9]{32})',key)
        if not match or abs(int(time.time()*1000)-int(match.group(1)))>120000:
            raise HTTPException(400,'command_id_expired_or_invalid')
    kind, action = body.get('kind'), body.get('action')
    if not isinstance(device, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', device):
        raise HTTPException(400, 'invalid_device_id')
    if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{8,100}', key):
        raise HTTPException(400, 'invalid_command_id')
    if kind not in ACTIONS or action not in ACTIONS[kind]:
        raise HTTPException(400, 'invalid_utility_action')
    payload = body.get('payload', {})
    if not isinstance(payload, dict):
        raise HTTPException(400, 'invalid_utility_payload')
    if kind == 'timer':
        if action == 'start':
            secs = payload.get('seconds')
            if type(secs) is not int or not 1 <= secs <= 86400:
                raise HTTPException(400, 'invalid_timer_seconds')
            label = payload.get('label', '')
            if not isinstance(label, str) or len(label) > 120:
                raise HTTPException(400, 'invalid_timer_label')
            payload = {'seconds': secs, 'label': label}
        else:
            tid = payload.get('timerId')
            if type(tid) is not int or tid <= 0:
                raise HTTPException(400, 'invalid_timer_id')
            payload = {'timerId': tid}
    elif kind == 'alarm':
        aid=payload.get('cloudAlarmId');trigger=payload.get('expectedNextTriggerTime')
        if not isinstance(aid,str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}',aid) or type(trigger) is not int or not 0<trigger<=9007199254740991:
            raise HTTPException(400,'invalid_skip_occurrence')
        payload={'cloudAlarmId':aid,'expectedNextTriggerTime':trigger}
    else:
        payload = {}
    return {'deviceId': device, 'commandId': key, 'kind': kind, 'action': action, 'payload': payload}
