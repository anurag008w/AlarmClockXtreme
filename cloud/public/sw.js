const CACHE="acx-cloud-v1";
const ASSETS=["/","/index.html","/styles.css","/app.js","/manifest.webmanifest"];
self.addEventListener("install",event=>event.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS))));
self.addEventListener("activate",event=>event.waitUntil(self.clients.claim()));
self.addEventListener("fetch",event=>{
  if(event.request.method!=="GET") return;
  const url=new URL(event.request.url);
  if(url.pathname.startsWith("/api/")) return;
  event.respondWith(caches.match(event.request).then(cached=>cached||fetch(event.request).then(res=>{
    const copy=res.clone(); caches.open(CACHE).then(c=>c.put(event.request,copy)); return res;
  }).catch(()=>cached)));
});