"""Small fixed-feed reader. Never fetch a URL supplied by a client."""
import asyncio
import re
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlparse
import httpx
from fastapi import HTTPException
FEEDS = {
 'google_top': ('Google Top', 'https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en'),
 'google_world': ('Google World', 'https://news.google.com/rss/headlines/section/topic/WORLD?hl=en-US&gl=US&ceid=US:en'),
 'google_tech': ('Google Tech', 'https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=en-US&gl=US&ceid=US:en'),
 'bbc': ('BBC', 'https://feeds.bbci.co.uk/news/rss.xml'),
 'npr': ('NPR', 'https://feeds.npr.org/1001/rss.xml'),
 'hn': ('Hacker News', 'https://hnrss.org/frontpage'),
}
_cache = {}
_lock = asyncio.Lock()
def tag(node): return node.tag.rsplit('}',1)[-1].lower()
def parse_feed(raw):
 if len(raw)>2_000_000 or b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
  raise ValueError('unsafe_or_large_feed')
 root=ET.fromstring(raw)
 if tag(root) not in {'rss','feed','rdf'}: raise ValueError('not_a_feed')
 rows=[]
 for entry in root.iter():
  if tag(entry) not in {'item','entry'}:continue
  values={tag(n): ''.join(n.itertext()).strip() for n in entry}
  link=values.get('link','')
  for n in entry:
   if tag(n)=='link' and n.attrib.get('rel','alternate')=='alternate' and n.attrib.get('href'):link=n.attrib['href']
  parts=urlparse(link)
  if parts.scheme not in {'https','http'} or not parts.netloc or parts.username or parts.password:continue
  title=values.get('title','')[:500]
  if not title:continue
  summary=re.sub('<[^>]*>','',values.get('description',values.get('summary','')))[:1000]
  rows.append({'title':title,'url':link,'summary':summary,'published':values.get('pubdate',values.get('published',values.get('updated','')))[:100]})
  if len(rows)>=100:break
 return rows
async def read_feed(key):
 if key not in FEEDS: raise HTTPException(400,'unsupported_feed_use_preset')
 async with _lock:
  prior=_cache.get(key);now=int(time.time()*1000)
  if prior and now-prior['fetchedAtMillis']<300000:return {**prior,'stale':False}
  try:
   async with httpx.AsyncClient(timeout=15,follow_redirects=False,trust_env=False) as client:
    async with client.stream('GET',FEEDS[key][1],headers={'User-Agent':'AlarmClockXtreme Cloud feed reader','Accept':'application/rss+xml, application/atom+xml, application/xml'}) as res:
     res.raise_for_status();data=bytearray()
     async for chunk in res.aiter_bytes():
      data.extend(chunk)
      if len(data)>2_000_000:raise ValueError('feed_too_large')
   result={'feed':key,'url':FEEDS[key][1],'fetchedAtMillis':now,'items':parse_feed(bytes(data))}
   _cache[key]=result
   return {**result,'stale':False}
  except (httpx.HTTPError,ValueError,ET.ParseError):
   if prior and now-prior['fetchedAtMillis']<48*3600000:return {**prior,'stale':True,'warning':'refresh_failed_cached_feed'}
   raise HTTPException(502,'news_feed_unavailable')
