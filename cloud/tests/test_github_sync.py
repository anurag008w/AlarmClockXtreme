import unittest

import github_sync


class AlarmMergeTests(unittest.TestCase):
    def test_higher_remote_version_beats_later_stale_timestamp(self):
        local = {
            "schema": 1,
            "items": {
                "a1": {
                    "id": "a1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:00.999999Z",
                    "deleted_at": None,
                    "payload": {"label": "stale server"},
                }
            },
        }
        remote = {
            "schema": 1,
            "items": {
                "a1": {
                    "id": "a1",
                    "version": 3,
                    "updated_at": "2026-09-30T00:00:00.100000Z",
                    "deleted_at": None,
                    "payload": {"label": "durable latest"},
                }
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertEqual(merged["items"]["a1"]["version"], 3)
        self.assertEqual(
            merged["items"]["a1"]["payload"]["label"],
            "durable latest",
        )

    def test_newer_tombstone_beats_stale_live_alarm(self):
        local = {
            "schema": 1,
            "updated_at": "2026-09-30T00:00:00.000001Z",
            "items": {
                "a1": {
                    "id": "a1",
                    "version": 3,
                    "updated_at": "2026-09-30T00:00:00.000002Z",
                    "deleted_at": "2026-09-30T00:00:00.000002Z",
                    "payload": None,
                }
            },
        }
        remote = {
            "schema": 1,
            "updated_at": "2026-09-30T00:00:00.000000Z",
            "items": {
                "a1": {
                    "id": "a1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:00.000001Z",
                    "deleted_at": None,
                    "payload": {"label": "old"},
                }
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertIsNotNone(merged["items"]["a1"]["deleted_at"])
        self.assertEqual(merged["items"]["a1"]["version"], 3)

    def test_remote_newer_edit_survives_stale_local_copy(self):
        local = {
            "schema": 1,
            "updated_at": "2026-09-30T00:00:00.000001Z",
            "items": {
                "a1": {
                    "id": "a1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:00.000001Z",
                    "deleted_at": None,
                    "payload": {"label": "stale local"},
                }
            },
        }
        remote = {
            "schema": 1,
            "updated_at": "2026-09-30T00:00:00.000002Z",
            "items": {
                "a1": {
                    "id": "a1",
                    "version": 3,
                    "updated_at": "2026-09-30T00:00:00.000002Z",
                    "deleted_at": None,
                    "payload": {"label": "new remote"},
                }
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertEqual(merged["items"]["a1"]["version"], 3)
        self.assertEqual(merged["items"]["a1"]["payload"]["label"], "new remote")

    def test_independent_alarm_changes_are_merged_not_replaced(self):
        local = {
            "schema": 1,
            "items": {
                "android": {
                    "id": "android",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:00.000002Z",
                    "deleted_at": None,
                    "payload": {"label": "phone"},
                }
            },
        }
        remote = {
            "schema": 1,
            "items": {
                "web": {
                    "id": "web",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:00.000001Z",
                    "deleted_at": None,
                    "payload": {"label": "browser"},
                }
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertEqual(set(merged["items"]), {"android", "web"})


if __name__ == "__main__":
    unittest.main()
