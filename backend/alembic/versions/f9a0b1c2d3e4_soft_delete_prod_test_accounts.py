"""Soft-delete the test accounts left in production by early test runs.

Production holds 18 accounts on 2026-11-02, read-only measurement. Thirteen are
test accounts that agents and repro sessions created before the beta existed
(April to September 2026). Each one is alone in its own household. Decision of
2026-11-02: delete them before the official launch.

Kept, on purpose: the two real members; `TestUser` of 2026-05-26 (a test account
we keep, minus its FCM token); `Tom` (demo account, household "new"); and the
duplicate Bob account of 2026-10-18.

What this does is what `DELETE /api/me` does, done in SQL:
- the account gets `deleted_at` (the nightly cleanup hard-deletes it after 30 days);
- its memberships are left (`left_at`, `former_member`);
- its households, now with no active member, get `archived_at` (purged after 365 days);
- its FCM tokens are deleted at once, plus the one of the kept `TestUser`.

Every account is matched on BOTH its id and its username, so the same ids in
another database (dev, beta, tests) match nothing and the migration is a no-op.
It aborts if a household of these accounts still has an active member who is not
one of them: deleting must never touch a real household.

Downgrade does nothing. The way back is the dump taken just before deploying
(`scripts/backup_prod.sh`).

Revision ID: f9a0b1c2d3e4
Revises: e7f8a9b0c1d2
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "f9a0b1c2d3e4"
down_revision: Union[str, None] = "e7f8a9b0c1d2"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# (id, username), as read in production on 2026-11-02.
TEST_ACCOUNTS = [
    ("0dff91fe-9e34-4648-a728-1de3ded145d6", "u_5554d68cc787"),  # TestUser, 2026-04-29
    ("9d1a43c6-a082-4ffc-8931-a32f8759ed90", "u_266df1655097"),  # JoinerBob
    ("1d48b2f8-7d86-4c28-befe-c7a68a85b197", "u_e79217864d67"),  # WSummaryUser
    ("f5e310e5-0ac0-4c30-9fee-cb2a63de86f6", "u_11eb81067356"),  # AddDeviceTester
    ("d0ffd559-3aa0-4f74-8a2c-c5217b9f6c20", "u_4cfc75dcabb5"),  # CTU
    ("38589c1b-0390-4329-9421-203e685a4c99", "u_55b911d50153"),  # LT
    ("6274fe8d-94da-4428-b570-e1ff75de5e03", "u_ac6c0e9ad9ed"),  # OBJ
    ("6f58a8e0-cab7-44d4-b15d-17545de3f4a6", "u_203cb6c57d06"),  # EXP
    ("ffcfcf32-5413-441f-8aaf-cf250e5ccff0", "u_003106453c1d"),  # ReproU
    ("3c565755-ee8e-4315-b76b-7ffd80527423", "u_6371780b89b4"),  # ReproU
    ("aa81a34d-004d-4098-a897-f344a32c0d9a", "u_f63619bd18ff"),  # ReproU
    ("231df0a8-ad89-4ef4-b64d-a49c0910f366", "u_04e99896b949"),  # Moi, 2026-07-29
    ("c788e3f2-7e6d-4aa7-af33-422a2d20b9c7", "u_dfe352ad7761"),  # Moi, 2026-09-21
]

# Kept account whose FCM token goes anyway.
KEPT_TESTUSER = ("6e6c0bcc-8da3-43d7-9837-38e9952b39b9", "u_a09138cfa43a")


def _values(pairs) -> str:
    return ", ".join(f"('{i}'::uuid, '{u}')" for i, u in pairs)


def upgrade() -> None:
    conn = op.get_bind()
    targets_sql = (
        "SELECT u.id FROM users u "
        f"JOIN (VALUES {_values(TEST_ACCOUNTS)}) AS t(id, username) "
        "ON u.id = t.id AND u.username = t.username "
        "WHERE u.deleted_at IS NULL"
    )
    conn.execute(sa.text(f"CREATE TEMP TABLE _test_users AS {targets_sql}"))
    conn.execute(sa.text(
        "CREATE TEMP TABLE _test_foyers AS "
        "SELECT DISTINCT foyer_id FROM user_foyers "
        "WHERE user_id IN (SELECT id FROM _test_users) AND left_at IS NULL"
    ))

    shared = conn.execute(sa.text(
        "SELECT count(*) FROM user_foyers "
        "WHERE foyer_id IN (SELECT foyer_id FROM _test_foyers) AND left_at IS NULL "
        "AND user_id NOT IN (SELECT id FROM _test_users)"
    )).scalar_one()
    if shared:
        raise RuntimeError(
            f"{shared} active member(s) outside the test accounts share their households: abort"
        )

    kept_id, kept_username = KEPT_TESTUSER
    conn.execute(sa.text(
        "DELETE FROM notification_tokens WHERE user_id IN (SELECT id FROM _test_users) "
        "OR user_id = (SELECT id FROM users WHERE id = CAST(:kid AS uuid) AND username = :kun)"
    ), {"kid": kept_id, "kun": kept_username})
    conn.execute(sa.text(
        "UPDATE user_foyers SET left_at = now(), role = 'former_member' "
        "WHERE user_id IN (SELECT id FROM _test_users) AND left_at IS NULL"
    ))
    conn.execute(sa.text(
        "UPDATE foyers SET archived_at = now() "
        "WHERE id IN (SELECT foyer_id FROM _test_foyers) AND archived_at IS NULL "
        "AND id NOT IN (SELECT foyer_id FROM user_foyers WHERE left_at IS NULL)"
    ))
    conn.execute(sa.text(
        "UPDATE users SET deleted_at = now(), last_active_foyer_id = NULL "
        "WHERE id IN (SELECT id FROM _test_users)"
    ))
    conn.execute(sa.text("DROP TABLE _test_users, _test_foyers"))


def downgrade() -> None:
    # Nothing to undo in SQL: the way back is the dump taken before deploying.
    pass
