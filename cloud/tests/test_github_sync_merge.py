import unittest

import github_sync


class GithubAlarmMergeTests(unittest.TestCase):
    def test_newer_remote_delete_beats_stale_local_live_row(self):
        local = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:01.000000Z",
                    "deleted_at": None,
                    "payload": {"label": "stale local"},
                }
            },
        }
        remote = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 3,
                    "updated_at": "2026-09-30T00:00:02.000000Z",
                    "deleted_at": "2026-09-30T00:00:02.000000Z",
                    "payload": None,
                }
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertIsNotNone(merged["items"]["alarm-1"]["deleted_at"])
        self.assertEqual(merged["items"]["alarm-1"]["version"], 3)

    def test_newer_local_delete_beats_stale_remote_live_row(self):
        local = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 3,
                    "updated_at": "2026-09-30T00:00:02.000000Z",
                    "deleted_at": "2026-09-30T00:00:02.000000Z",
                    "payload": None,
                }
            },
        }
        remote = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:01.000000Z",
                    "deleted_at": None,
                    "payload": {"label": "stale remote"},
                }
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertIsNotNone(merged["items"]["alarm-1"]["deleted_at"])
        self.assertEqual(merged["items"]["alarm-1"]["version"], 3)

    def test_newer_remote_edit_beats_stale_local_edit_without_data_loss(self):
        local = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:01.000000Z",
                    "deleted_at": None,
                    "payload": {"label": "local stale"},
                },
                "local-only": {
                    "id": "local-only",
                    "version": 1,
                    "updated_at": "2026-09-30T00:00:01.500000Z",
                    "deleted_at": None,
                    "payload": {"label": "keep me"},
                },
            },
        }
        remote = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 4,
                    "updated_at": "2026-09-30T00:00:03.000000Z",
                    "deleted_at": None,
                    "payload": {"label": "new remote"},
                },
                "remote-only": {
                    "id": "remote-only",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:02.000000Z",
                    "deleted_at": None,
                    "payload": {"label": "keep remote"},
                },
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertEqual(merged["items"]["alarm-1"]["payload"]["label"], "new remote")
        self.assertIn("local-only", merged["items"])
        self.assertIn("remote-only", merged["items"])

    def test_equal_timestamp_tie_never_resurrects(self):
        local = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:02.000000Z",
                    "deleted_at": "2026-09-30T00:00:02.000000Z",
                    "payload": None,
                }
            },
        }
        remote = {
            "schema": 1,
            "items": {
                "alarm-1": {
                    "id": "alarm-1",
                    "version": 2,
                    "updated_at": "2026-09-30T00:00:02.000000Z",
                    "deleted_at": None,
                    "payload": {"label": "resurrection"},
                }
            },
        }

        merged = github_sync._merge_alarm_scope(local, remote)

        self.assertIsNotNone(merged["items"]["alarm-1"]["deleted_at"])


if __name__ == "__main__":
    unittest.main()
