import asyncio
import copy
import json
import os
import unittest
from unittest.mock import AsyncMock, patch

import test_settings  # sets test env before importing server
import fcm
import server

TOKEN = "t" * 60


class FcmUnitTests(unittest.IsolatedAsyncioTestCase):
    def test_not_configured_is_noop(self):
        with patch.dict(os.environ, {"FCM_SERVICE_ACCOUNT_JSON": ""}):
            self.assertFalse(fcm.is_configured())
            self.assertEqual(asyncio.run(fcm.send_sync({"d": TOKEN})), {"sent": 0, "dead": [], "failed": 0})

    def test_bad_json_is_not_configured(self):
        with patch.dict(os.environ, {"FCM_SERVICE_ACCOUNT_JSON": "{nope"}):
            self.assertFalse(fcm.is_configured())
        with patch.dict(os.environ, {"FCM_SERVICE_ACCOUNT_JSON": json.dumps({"project_id": "x"})}):
            self.assertFalse(fcm.is_configured())

    def test_token_validation(self):
        self.assertTrue(fcm.valid_push_token(TOKEN))
        for bad in (None, 5, "", "short", "a b" * 20, "x" * 5000):
            self.assertFalse(fcm.valid_push_token(bad))

    def test_message_is_data_only_and_carries_no_alarm_content(self):
        msg = fcm.build_message(TOKEN, "change")["message"]
        self.assertNotIn("notification", msg)
        self.assertEqual(msg["data"], {"type": "sync", "reason": "change"})
        self.assertEqual(msg["android"]["priority"], "HIGH")
        self.assertTrue(fcm.build_message(TOKEN, validate_only=True)["validate_only"])


class FakeResponse:
    def __init__(self, status, body=None):
        self.status_code = status
        self._body = body or {}

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(self.status_code)


class FakeClient:
    def __init__(self, plan):
        self.plan = plan
        self.sent = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def post(self, url, headers=None, json=None, data=None):
        if data is not None:
            return FakeResponse(200, {"access_token": "at", "expires_in": 3600})
        token = json["message"]["token"]
        self.sent.append(token)
        return self.plan.get(token, FakeResponse(200, {"name": "ok"}))


class FcmSendTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives import serialization
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption()).decode()
        self.env = patch.dict(os.environ, {"FCM_SERVICE_ACCOUNT_JSON": json.dumps({
            "project_id": "p", "client_email": "sa@p.iam.gserviceaccount.com",
            "private_key": pem, "private_key_id": "kid"})})
        self.env.start()
        fcm._cached.update(token="", exp=0.0)

    def tearDown(self):
        self.env.stop()

    async def test_sends_and_reports_dead_tokens(self):
        dead = FcmClientPlan = {"d" * 60: FakeResponse(404, {"error": {"status": "NOT_FOUND", "details": [{"errorCode": "UNREGISTERED"}]}}),
                                "f" * 60: FakeResponse(500, {"error": {"status": "INTERNAL"}})}
        client = FakeClient(dead)
        with patch.object(fcm.httpx, "AsyncClient", return_value=client):
            out = await fcm.send_sync({"ok": TOKEN, "dead": "d" * 60, "flaky": "f" * 60})
        self.assertEqual(out, {"sent": 1, "dead": ["dead"], "failed": 1})

    async def test_network_failure_never_raises(self):
        class Boom(FakeClient):
            async def post(self, *a, **k):
                raise OSError("down")
        with patch.object(fcm.httpx, "AsyncClient", return_value=Boom({})):
            out = await fcm.send_sync({"ok": TOKEN})
        self.assertEqual(out["sent"], 0)
        self.assertEqual(out["failed"], 1)


class ServerPushTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fixture = test_settings.SettingsTests()
        await self.fixture.asyncSetUp()
        self.store = self.fixture.store
        self.user = self.fixture.user

    async def asyncTearDown(self):
        await self.fixture.asyncTearDown()

    async def test_register_stores_token_and_keeps_it_for_old_builds(self):
        await server.register_device({"deviceId": "p1", "pushToken": TOKEN, "appVersion": "1.16.0"}, self.user)
        self.assertEqual(self.store[("u", "devices")]["devices"]["p1"]["pushToken"], TOKEN)
        await server.register_device({"deviceId": "p1", "appVersion": "1.15.52"}, self.user)
        self.assertEqual(self.store[("u", "devices")]["devices"]["p1"]["pushToken"], TOKEN)
        await server.register_device({"deviceId": "p1", "pushToken": "bad token"}, self.user)
        self.assertEqual(self.store[("u", "devices")]["devices"]["p1"]["pushToken"], TOKEN)
        await server.register_device({"deviceId": "p1", "pushToken": "n" * 70}, self.user)
        self.assertEqual(self.store[("u", "devices")]["devices"]["p1"]["pushToken"], "n" * 70)

    async def test_device_list_never_exposes_tokens(self):
        await server.register_device({"deviceId": "p1", "pushToken": TOKEN}, self.user)
        listing = await server.utility_devices(self.user)
        self.assertNotIn(TOKEN, json.dumps(listing))
        self.assertTrue(listing["devices"][0]["push"])

    async def test_notify_targets_only_users_devices_and_prunes_dead(self):
        self.store[("u", "devices")] = {"devices": {
            "a": {"pushToken": TOKEN}, "b": {"platform": "android"}, "c": {"pushToken": "c" * 60}}}
        self.store[("other", "devices")] = {"devices": {"z": {"pushToken": "z" * 60}}}
        sender = AsyncMock(return_value={"sent": 1, "dead": ["c"], "failed": 0})
        with patch.object(server.fcm, "send_sync", sender):
            await server.notify_devices("u", "change")
        sender.assert_awaited_once_with({"a": TOKEN, "c": "c" * 60}, "change")
        devices = self.store[("u", "devices")]["devices"]
        self.assertIn("pushToken", devices["a"])
        self.assertNotIn("pushToken", devices["c"])
        self.assertIn("pushToken", self.store[("other", "devices")]["devices"]["z"])

    async def test_bursts_coalesce_into_one_push(self):
        server.PUSH_DEBOUNCE_SECONDS = 0.05
        calls = []

        async def fake(user_id, reason):
            calls.append((user_id, reason))
        with patch.object(server, "notify_devices", fake):
            for _ in range(5):
                server.schedule_push("u", "change")
            await asyncio.sleep(0.2)
            server.schedule_push("u", "change")
            await asyncio.sleep(0.2)
        self.assertEqual(calls, [("u", "change"), ("u", "change")])

    async def test_middleware_pushes_only_after_successful_writes(self):
        from httpx import ASGITransport, AsyncClient
        import jwt
        token = jwt.encode({"sub": "u", "email": "t@example.invalid"}, server.JWT_SECRET, algorithm="HS256")
        scheduled = []
        with patch.dict(os.environ, {"FCM_SERVICE_ACCOUNT_JSON": json.dumps(
                {"project_id": "p", "client_email": "e", "private_key": "k"})}), \
                patch.object(server, "schedule_push", lambda u, r: scheduled.append((u, r))):
            async with AsyncClient(transport=ASGITransport(app=server.app), base_url="http://t") as c:
                h = {"Authorization": "Bearer " + token}
                ok = await c.put("/api/settings", json={"payload": {"bedtimeHour": 22}}, headers=h)
                self.assertEqual(ok.status_code, 200)
                self.assertEqual(scheduled, [("u", "change")])
                bad = await c.put("/api/settings", json={"payload": {"bedtimeHour": 99}}, headers=h)
                self.assertEqual(bad.status_code, 400)
                await c.get("/api/settings", headers=h)
                await c.put("/api/dashboard/p1", json={}, headers=h)
                self.assertEqual(len(scheduled), 1)

    async def test_no_push_when_not_configured(self):
        from httpx import ASGITransport, AsyncClient
        import jwt
        token = jwt.encode({"sub": "u", "email": "t@example.invalid"}, server.JWT_SECRET, algorithm="HS256")
        scheduled = []
        with patch.dict(os.environ, {"FCM_SERVICE_ACCOUNT_JSON": ""}), \
                patch.object(server, "schedule_push", lambda u, r: scheduled.append(1)):
            async with AsyncClient(transport=ASGITransport(app=server.app), base_url="http://t") as c:
                await c.put("/api/settings", json={"payload": {"bedtimeHour": 22}},
                            headers={"Authorization": "Bearer " + token})
        self.assertEqual(scheduled, [])


if __name__ == "__main__":
    unittest.main()
