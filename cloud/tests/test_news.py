import unittest
from unittest.mock import patch
import news
class NewsTests(unittest.IsolatedAsyncioTestCase):
 def test_rss_and_unsafe_links(self):
  raw=b'<rss><channel><item><title>Test &amp; News</title><link>https://example.org/a</link><description>&lt;b&gt;Text&lt;/b&gt;</description></item><item><title>Bad</title><link>javascript:alert(1)</link></item></channel></rss>'
  rows=news.parse_feed(raw)
  self.assertEqual(len(rows),1);self.assertEqual(rows[0]['title'],'Test & News');self.assertEqual(rows[0]['summary'],'Text')
 def test_atom(self):
  rows=news.parse_feed(b'<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>A</title><link href="https://example.org/a"/><updated>2026-10-06</updated></entry></feed>')
  self.assertEqual(rows[0]['url'],'https://example.org/a')
 def test_reject_entities_large_nonfeed(self):
  for data in [b'<!DOCTYPE x><rss/>',b'<!ENTITY x "x"><rss/>',b'x'*2000001,b'<html/>']:
   with self.assertRaises(ValueError):news.parse_feed(data)
 async def test_custom_urls_never_fetched(self):
  with self.assertRaises(news.HTTPException):await news.read_feed('http://127.0.0.1/')
 async def test_fresh_cache(self):
  with patch.dict(news._cache,{'bbc':{'fetchedAtMillis':int(news.time.time()*1000),'items':[]}},clear=True):
   self.assertFalse((await news.read_feed('bbc'))['stale'])
 async def test_failed_refresh_stale_is_explicit_and_bounded(self):
  from unittest.mock import AsyncMock
  now=int(news.time.time()*1000)
  for age,allowed in [(3600000,True),(49*3600000,False)]:
   with patch.dict(news._cache,{'bbc':{'fetchedAtMillis':now-age,'items':[]}},clear=True),patch.object(news.httpx,'AsyncClient',side_effect=news.httpx.ConnectError('offline')):
    if allowed:self.assertTrue((await news.read_feed('bbc'))['stale'])
    else:
     with self.assertRaises(news.HTTPException):await news.read_feed('bbc')
