"""Android challenge reference gates, shared across cloud and CLI."""
from fastapi import HTTPException
REQUIRED={'NFC_SCAN':'nfcTagId','BARCODE_SCAN':'barcodeValue','PHOTO_MATCH':'photoMatchUri','WIFI_CONNECT':'wifiDismissSsid'}
TYPES={'NONE','MATH_EASY','MATH_MEDIUM','MATH_HARD','SHAKE','SEQUENCE','MEMORY_PATTERN','TYPING','VOICE_PHRASE','HANDWRITING','WALK_STEPS','NFC_SCAN','BARCODE_SCAN','PHOTO_MATCH','SQUAT','WIFI_CONNECT','MAZE','COUNT_SHEEP','SIMON_SAYS','DATE_BACKWARDS','STROOP','ROCK_PAPER_SCISSORS','EMOJI_MEMORY','TYPING_SPEED','WORDLE','PVT','SPOT_DIFFERENCE','CHESS_MATE','RSVP_READING','PUSH_UP','PLANK_HOLD'}
def validate(payload):
 active=payload.get('challengeType','NONE')
 chain=payload.get('challengeChain','')
 if not isinstance(active,str) or not isinstance(chain,str):raise HTTPException(400,'invalid_challenge_type')
 types={active}|{value.strip() for value in chain.split(',') if value.strip()}
 if not types<=TYPES:raise HTTPException(400,'unknown_challenge_type')
 missing=[kind for kind in sorted(types) if kind in REQUIRED and (not isinstance(payload.get(REQUIRED[kind]),str) or not payload[REQUIRED[kind]].strip())]
 if missing:raise HTTPException(400,'missing_challenge_references:'+','.join(missing))
