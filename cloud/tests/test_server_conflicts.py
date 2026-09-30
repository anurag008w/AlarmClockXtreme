import asyncio
import copy
import os
import unittest
from unittest.mock import AsyncMock, patch

os.environ.setdefault("JWT_SECRET", "test-secret")
os.environ["GH_TOKEN"] = os.environ.get("GH_TOKEN") or "test-token"
os.environ["GITHUB_SYNC_ENABLED"] = "false"

import server


class AlarmConflictTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.records = {}

        async def fake_alarms_record(user_id):
            return self.records.setdefault(user_id, {"items": {}})

        async def fake_persist(user_id, record, **_kwargs):
            self.records[user_id] = copy.deepcopy(record)
            return record

        self.patches = patch.multiple(
            server,
            alarms_record=AsyncMock(side_effect=fake_alarms_record),
            persist_alarm_record=AsyncMock(side_effect=fake_persist),
            audit=AsyncMock(),
        )
        self.patches.start()

    async def asyncTearDown(self):
        self.patches.stop()

    async def test_updated_at_keeps_subsecond_precision(self):
        first = server.usersync.now_utc()
        second = server.usersync.now_utc()
        self.assertRegex(first, r"\.\d{6}Z$")
        self.assertRegex(second, r"\.\d{6}Z$")

    async def test_refresh_pulls_clean_dataset_without_push(self):
        with patch.object(server.github_sync, "ensure_current", return_value=True) as ensure_current, \
             patch.object(server.github_sync, "has_data_changed", return_value=False), \
             patch.object(server.github_sync, "push_data", return_value=True) as push_data:
            result = await server.refresh_sync({"id": "user-1", "email": "user@example.com"})

        self.assertTrue(result["ok"])
        ensure_current.assert_any_call(0.0)
        push_data.assert_not_called()

    async def test_refresh_flushes_pending_local_write_before_pull(self):
        with patch.object(server.github_sync, "ensure_current", return_value=True) as ensure_current, \
             patch.object(server.github_sync, "has_data_changed", return_value=True), \
             patch.object(server.github_sync, "push_data", return_value=True) as push_data:
            result = await server.refresh_sync({"id": "user-1", "email": "user@example.com"})

        self.assertTrue(result["ok"])
        ensure_current.assert_any_call(0.0)
        push_data.assert_called_once()

    async def test_alarm_reads_reconcile_to_durable_github_head(self):
        await server.alarms_record("user-1")
        with patch.object(
            server.github_sync,
            "ensure_current",
            return_value=True,
        ) as ensure_current:
            response = await server.get_alarms(
                since="1970-01-01T00:00:00Z",
                user={"id": "user-1", "email": "u@example.com"},
            )
        ensure_current.assert_called_once_with(2.0)
        self.assertEqual(response["alarms"], [])
        self.assertEqual(response["cursor"], "1970-01-01T00:00:00Z")

    async def test_alarm_read_fails_closed_when_durable_refresh_fails(self):
        with patch.object(
            server.github_sync,
            "ensure_current",
            return_value=False,
        ):
            with self.assertRaises(server.HTTPException) as ctx:
                await server.get_alarms(
                    since="1970-01-01T00:00:00Z",
                    user={"id": "user-1", "email": "u@example.com"},
                )
        self.assertEqual(ctx.exception.status_code, 503)
        self.assertEqual(ctx.exception.detail, "github_refresh_failed_retry")

    async def test_stale_update_is_rejected(self):
        created = await server.mutate_alarm(
            "user-1", "alarm-1", {"hour": 7, "minute": 0, "label": "Morning"}
        )
        self.assertEqual(created["version"], 1)

        updated = await server.mutate_alarm(
            "user-1",
            "alarm-1",
            {"hour": 8, "minute": 0, "label": "Study"},
            expected=1,
        )
        self.assertEqual(updated["version"], 2)

        with self.assertRaises(server.HTTPException) as ctx:
            await server.mutate_alarm(
                "user-1",
                "alarm-1",
                {"hour": 9, "minute": 0, "label": "Stale"},
                expected=1,
            )
        self.assertEqual(ctx.exception.status_code, 409)
        self.assertEqual(ctx.exception.detail, "version_conflict")
        self.assertEqual(self.records["user-1"]["items"]["alarm-1"]["version"], 2)
        self.assertEqual(
            self.records["user-1"]["items"]["alarm-1"]["payload"]["label"],
            "Study",
        )

    async def test_delete_tombstone_cannot_be_resurrected(self):
        await server.mutate_alarm(
            "user-1", "alarm-1", {"hour": 7, "minute": 0, "label": "Morning"}
        )
        deleted = await server.mutate_alarm(
            "user-1", "alarm-1", None, delete=True, expected=1
        )
        self.assertEqual(deleted["version"], 2)
        self.assertIsNotNone(deleted["deletedAt"])

        with self.assertRaises(server.HTTPException) as ctx:
            await server.mutate_alarm(
                "user-1",
                "alarm-1",
                {"hour": 8, "minute": 0, "label": "Resurrect"},
                expected=2,
            )
        self.assertEqual(ctx.exception.status_code, 409)
        self.assertEqual(ctx.exception.detail, "alarm_deleted_conflict")
        self.assertIsNotNone(
            self.records["user-1"]["items"]["alarm-1"]["deleted_at"]
        )

    async def test_missing_expected_version_cannot_overwrite_existing_alarm(self):
        await server.mutate_alarm(
            "user-1", "alarm-1", {"hour": 7, "minute": 0, "label": "Morning"}
        )
        with self.assertRaises(server.HTTPException) as ctx:
            await server.mutate_alarm(
                "user-1",
                "alarm-1",
                {"hour": 8, "minute": 0, "label": "Unsafe overwrite"},
                expected=0,
            )
        self.assertEqual(ctx.exception.status_code, 409)
        self.assertEqual(ctx.exception.detail, "version_required")

    async def test_simultaneous_same_version_writes_serialize(self):
        await server.mutate_alarm(
            "user-1", "alarm-1", {"hour": 7, "minute": 0, "label": "Morning"}
        )

        results = await asyncio.gather(
            server.mutate_alarm(
                "user-1",
                "alarm-1",
                {"hour": 8, "minute": 0, "label": "Android"},
                expected=1,
            ),
            server.mutate_alarm(
                "user-1",
                "alarm-1",
                {"hour": 9, "minute": 0, "label": "Web"},
                expected=1,
            ),
            return_exceptions=True,
        )

        successes = [result for result in results if not isinstance(result, Exception)]
        conflicts = [result for result in results if isinstance(result, server.HTTPException)]

        self.assertEqual(len(successes), 1)
        self.assertEqual(len(conflicts), 1)
        self.assertEqual(conflicts[0].status_code, 409)
        self.assertEqual(conflicts[0].detail, "version_conflict")
        self.assertEqual(self.records["user-1"]["items"]["alarm-1"]["version"], 2)

    async def test_duplicate_create_burst_is_rejected(self):
        created = await server.mutate_alarm(
            "user-1",
            "alarm-1",
            {"hour": 8, "minute": 48, "label": "Test - Divya"},
        )
        self.assertEqual(created["version"], 1)

        # Same logical alarm submitted again under a fresh id (double click,
        # Enter resubmit, client retry) must not become a second row.
        with self.assertRaises(server.HTTPException) as ctx:
            await server.mutate_alarm(
                "user-1",
                "alarm-2",
                {"hour": 8, "minute": 48, "label": "Test - Divya"},
            )
        self.assertEqual(ctx.exception.status_code, 409)
        self.assertEqual(ctx.exception.detail, "duplicate_create_suspected")
        self.assertNotIn("alarm-2", self.records["user-1"]["items"])

    async def test_duplicate_create_allowed_after_window(self):
        await server.mutate_alarm(
            "user-1", "alarm-1", {"hour": 6, "minute": 0, "label": "Gym"}
        )
        with patch.object(server, "DUPLICATE_CREATE_WINDOW_SECONDS", 0):
            created = await server.mutate_alarm(
                "user-1", "alarm-2", {"hour": 6, "minute": 0, "label": "Gym"}
            )
        self.assertEqual(created["version"], 1)

    async def test_tombstoned_identical_alarm_does_not_block_create(self):
        await server.mutate_alarm(
            "user-1", "alarm-1", {"hour": 21, "minute": 47, "label": "Test"}
        )
        await server.mutate_alarm(
            "user-1", "alarm-1", None, delete=True, expected=1
        )
        created = await server.mutate_alarm(
            "user-1", "alarm-2", {"hour": 21, "minute": 47, "label": "Test"}
        )
        self.assertEqual(created["version"], 1)

    async def test_distinct_payload_create_is_not_blocked(self):
        await server.mutate_alarm(
            "user-1", "alarm-1", {"hour": 7, "minute": 0, "label": "Wake Up"}
        )
        created = await server.mutate_alarm(
            "user-1", "alarm-2", {"hour": 7, "minute": 0, "label": "Wake Up 2"}
        )
        self.assertEqual(created["version"], 1)

if __name__ == "__main__":
    unittest.main()
