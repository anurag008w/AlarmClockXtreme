"""Weather for coordinates explicitly selected by the web/CLI user, not phone GPS."""
import math
import time
import httpx
from fastapi import HTTPException
_cache={}
async def public_json(url,params):
 try:
  async with httpx.AsyncClient(timeout=15,follow_redirects=False,trust_env=False) as client:
   async with client.stream('GET',url,params=params) as response:
    response.raise_for_status();raw=bytearray()
    async for chunk in response.aiter_bytes():
     raw.extend(chunk)
     if len(raw)>2_000_000:raise ValueError('response_too_large')
   import json
   data=json.loads(raw)
   if not isinstance(data,dict):raise ValueError('invalid_response')
   return data
 except (httpx.HTTPError,ValueError):raise HTTPException(502,'weather_provider_unavailable')
async def cities(name):
 name=name.strip()
 if not 2<=len(name)<=100:raise HTTPException(400,'city_name_length_2_to_100')
 data=await public_json('https://geocoding-api.open-meteo.com/v1/search',{'name':name,'count':8,'language':'en','format':'json'})
 return {'results':[{k:row.get(k) for k in ['name','country','admin1','latitude','longitude','timezone']} for row in data.get('results',[])[:8]]}
async def forecast(lat,lon,unit):
 if not math.isfinite(lat) or not math.isfinite(lon) or not -90<=lat<=90 or not -180<=lon<=180 or unit not in {'celsius','fahrenheit'}:raise HTTPException(400,'invalid_weather_location_or_unit')
 key=(round(lat,4),round(lon,4),unit);now=int(time.time()*1000);prior=_cache.get(key)
 if prior and now-prior['fetchedAtMillis']<600000:return {**prior,'stale':False}
 try:
  data=await public_json('https://api.open-meteo.com/v1/forecast',{'latitude':lat,'longitude':lon,'current':'temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,apparent_temperature','hourly':'temperature_2m,precipitation_probability','daily':'temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max','temperature_unit':unit,'forecast_days':3,'timezone':'auto'})
  if len(_cache)>=100:_cache.pop(next(iter(_cache)))
  try:
   data['airQuality']=await public_json('https://air-quality-api.open-meteo.com/v1/air-quality',{'latitude':lat,'longitude':lon,'current':'us_aqi,pm10,pm2_5,ozone,nitrogen_dioxide,carbon_monoxide,sulphur_dioxide,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen','timezone':'auto','forecast_days':1})
  except HTTPException:data['airQualityWarning']='air_quality_unavailable'
  data={**data,'fetchedAtMillis':now,'source':'https://open-meteo.com/'};_cache[key]=data
  return {**data,'stale':False}
 except HTTPException:
  if prior and now-prior['fetchedAtMillis']<6*3600000:return {**prior,'stale':True}
  raise
