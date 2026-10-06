import asyncio,json
from pathlib import Path
from playwright.async_api import async_playwright
ROOT=Path(__file__).resolve().parents[2]/'cloud/public'
async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch(headless=True,args=['--no-sandbox'])
  for width in (320,390,1280):
   page=await browser.new_page(viewport={'width':width,'height':900})
   rows=[{'id':'test-a','version':1,'updatedAt':'2026-10-06T07:00:00Z','payload':{'hour':7,'minute':0,'label':'Study alarm','isEnabled':True,'repeatDays':['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY'],'ringtoneUri':'content://test/polozhenie','volume':85}}, {'id':'test-b','version':1,'updatedAt':'2026-10-06T07:00:00Z','payload':{'hour':8,'minute':30,'label':'Weekend alarm','isEnabled':False,'repeatDays':['SATURDAY','SUNDAY'],'ringtoneUri':'content://test/second','volume':60}}]
   settings={'payload':{'bedtimeHour':23},'version':1}
   async def handler(route):
    path=route.request.url.split('qa.invalid',1)[1].split('?')[0]
    if path.startswith('/api/'):
     if path=='/api/me':data={'user':{'id':'isolated-test','email':'test@example.invalid'}}
     elif path=='/api/alarms':data={'alarms':rows,'cursor':'2026-10-06T07:00:00Z'}
     elif path=='/api/settings':
      if route.request.method=='PUT':settings.update(payload=route.request.post_data_json['payload'],version=settings['version']+1)
      data=settings
     elif path=='/api/audit':data={'items':[]}
     elif path.startswith('/api/alarms/') and route.request.method=='PUT':
      body=route.request.post_data_json;row=next(r for r in rows if r['id']==path.split('/')[-1]);row.update(payload=body['payload'],version=row['version']+1);data=row
     else:data={'ok':True}
     await route.fulfill(json=data);return
    f=ROOT/('index.html' if path=='/' else path.lstrip('/'))
    await route.fulfill(path=str(f))
   await page.route('https://qa.invalid/**',handler)
   await page.add_init_script("localStorage.setItem('acx_token','isolated-test')")
   await page.goto('https://qa.invalid/')
   await page.wait_for_selector('.alarm')
   assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth'),f'overflow {width}'
   await page.screenshot(path=f'/tmp/alarm-web-{width}.png',full_page=True)
   await page.locator('[data-edit="test-a"]').click()
   await page.get_by_role('button',name='Sound',exact=True).click()
   await page.wait_for_selector('[data-ringtone-preset]')
   assert await page.locator('[data-ringtone-preset] option').count()==4
   await page.locator('[data-field="volume"]').fill('72')
   await page.screenshot(path=f'/tmp/alarm-sound-{width}.png',full_page=True)
   await page.locator('#saveAlarmBtn').click()
   await page.wait_for_function('!document.querySelector("#alarmDialog").open')
   assert rows[0]['payload']['volume']==72
   assert rows[0]['payload']['ringtoneUri']=='content://test/polozhenie'
   await page.locator('[data-toggle="test-b"]').click()
   await page.wait_for_function('document.querySelector("[data-toggle=\\"test-b\\"]").getAttribute("aria-checked")==="true"')
   # Every challenge and editor page exists, and all section controls bind.
   await page.locator('[data-edit="test-a"]').click()
   await page.get_by_role('button',name='Dismiss',exact=True).click()
   assert await page.locator('[data-field="challengeType"] option').count()==31
   for name in ('Overview','Sound','Dismiss','Schedule','Wake','Integrations','Advanced'):
    await page.get_by_role('button',name=name,exact=True).click()
    assert await page.locator('.editor-section [data-field]').count()>0
   await page.locator('#closeEditorBtn').click()
   await page.locator('[data-tab="settings"]').click()
   await page.wait_for_selector('#settingsEditor [data-field="bedtimeHour"]')
   assert await page.locator('#settingsEditor [data-field]').count()==56
   await page.locator('#settingsEditor [data-field="bedtimeHour"]').fill('22')
   await page.locator('#settingsEditor [data-field="bedtimeHour"]').dispatch_event('change')
   await page.locator('#saveSettingsBtn').click()
   await page.wait_for_function('document.querySelector("#settingsStatus").textContent.includes("saved and checked")')
   assert settings['payload']['bedtimeHour']==22
   await page.evaluate('window.scrollTo(0,0)')
   await page.screenshot(path=f'/tmp/alarm-settings-{width}.png',full_page=False)
   await page.locator('[data-tab="alarms"]').click()
   await page.locator('#alarmSearch').fill('Weekend')
   assert await page.locator('.alarm').count()==1
   await page.locator('#alarmSearch').fill('')
   await page.locator('[data-alarm-menu="test-a"] summary').click()
   await page.locator('[data-copy="test-a"]').click()
   assert await page.evaluate('editorDraft.isEnabled') is False
   await page.locator('#closeEditorBtn').click()
   await page.locator('[data-quick="20"]').click()
   assert await page.evaluate('editorDraft.specificDate.length')==10
   await page.locator('#closeEditorBtn').click()
   # Full refresh failure must not erase the cached list.
   await page.route('https://qa.invalid/api/alarms?**',lambda r:r.fulfill(status=503,json={'detail':'github_refresh_failed_retry'}))
   await page.evaluate('syncNow({forceFull:true}).catch(()=>{})')
   assert await page.locator('.alarm').count()==2
   assert await page.locator('#syncState').inner_text()=='sync error'
   print('PASS',width,'layout, sound save, toggle, 31 challenges, 7 editor pages, 56 settings save/readback, search, disabled duplicate draft, quick date draft, failed-full-refresh')
   await page.close()
  await browser.close()
asyncio.run(main())
