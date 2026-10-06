import unittest
from unittest.mock import patch,AsyncMock
import today
class TodayTests(unittest.IsolatedAsyncioTestCase):
 async def test_invalid_coordinates_and_units(self):
  for lat,lon,unit in [(91,0,'celsius'),(0,181,'celsius'),(float('nan'),0,'celsius'),(0,0,'bad')]:
   with self.assertRaises(today.HTTPException):await today.forecast(lat,lon,unit)
 async def test_city_input_bounded(self):
  for name in ('','a','a'*101):
   with self.assertRaises(today.HTTPException):await today.cities(name)
 async def test_forecast_cache_and_stale(self):
  data={'current':{'temperature_2m':20},'daily':{'time':[]}}
  with patch.dict(today._cache,{},clear=True),patch.object(today,'public_json',AsyncMock(return_value=data)) as fetch:
   result=await today.forecast(10,20,'celsius');self.assertFalse(result['stale'])
   await today.forecast(10,20,'celsius');self.assertEqual(fetch.await_count,2)
   today._cache[(10,20,'celsius')]['fetchedAtMillis']-=3600000
   fetch.side_effect=today.HTTPException(502,'offline')
   self.assertTrue((await today.forecast(10,20,'celsius'))['stale'])
   today._cache[(10,20,'celsius')]['fetchedAtMillis']-=6*3600000
   with self.assertRaises(today.HTTPException):await today.forecast(10,20,'celsius')
 async def test_city_response_has_only_public_fields(self):
  with patch.object(today,'public_json',AsyncMock(return_value={'results':[{'name':'Test','latitude':1,'secret':'no'}]})):
   rows=(await today.cities('Test'))['results'];self.assertNotIn('secret',rows[0])
