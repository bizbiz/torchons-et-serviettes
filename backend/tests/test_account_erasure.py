"""What anonymization erases, and what it keeps for the members who stay (privacy decisions 3 and
4 of 2026-11-15, app/services/account_erasure.py).

PATHS, enumerated before the cases:
  Anonymization of B, 30 days after B left (the nightly anonymize_departed_users):
  (1) every personal row of B gone (affinities, badges, guide views, objective proposals and
      answers, settings, tokens...), B's completion notes and SSID emptied, B's change_log
      purged; A's rows, notes, SSID, change_log and statistics untouched ............ COVERED
  (2) B's change_log row is the newest of the table: kept unsigned, the counter never goes
      back ........................................................................... COVERED
  (3) B's solo task HAD a completion: deleted with it, without failing the job; a solo task
      of B that A also completed is kept, A's completion with it ...................... COVERED
  Wi-Fi names (SSID):
  (4) leaving the household empties the leaver's SSID at once, not the stayer's ...... COVERED
  (5) PUT /api/settings no longer copies home_ssids into change_log ................. COVERED
  (6) the migration strips the key from rows already written, twice in a row ........ COVERED
  The users row:
  (7) kept while a household still holds B's completions (case 1) ................... COVERED
  (8) deleted once B's last household is purged, completions and change_log included . COVERED
non couvert : a note B wrote on ANOTHER member's completion stays (no author column on
task_completions) ; a household whose purge is postponed because it holds the newest
change_log row is checked through its effect (8), not on its own.
"""

from __future__ import annotations

import importlib.util
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
import pytest_asyncio
from sqlalchemy import func, select, text

from app.auth import create_token
from app.utils import rate_limit

OLD = datetime(2020, 1, 1, tzinfo=timezone.utc)
MIGRATION = Path(__file__).parents[1] / "alembic" / "versions" / "a4d9e2c7b5f1_strip_ssid_from_change_log.py"


@pytest.fixture(autouse=True)
def no_rate_limit():
    rate_limit.reset()
    yield
    rate_limit.reset()


def _headers(user_id, foyer_id) -> dict:
    return {"Authorization": f"Bearer {create_token(str(user_id), 'dev-e', foyer_id=str(foyer_id))}"}


async def _seed_people(session, ids):
    from app.models.foyer import Foyer
    from app.models.task import Task
    from app.models.task_assignment import TaskAssignment
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    session.add(Foyer(id=ids["foyer"], name="Erasure household"))
    await session.flush()
    for key, name in (("a", "Alma"), ("b", "Bruno")):
        session.add(User(id=ids[key], username=f"er{key}-{ids[key].hex[:10]}", display_name=name,
                         last_active_foyer_id=ids["foyer"]))
    await session.flush()
    session.add_all([
        UserFoyer(user_id=ids["a"], foyer_id=ids["foyer"], role="admin", home_ssids="BoxA",
                  daily_objective_minutes=20, objective_display_unit="tasks"),
        UserFoyer(user_id=ids["b"], foyer_id=ids["foyer"], role="member", home_ssids="BoxB|Livebox",
                  daily_objective_minutes=30, objective_display_unit="tasks"),
        Task(id=ids["shared"], foyer_id=ids["foyer"], title="shared"),
        Task(id=ids["solo"], foyer_id=ids["foyer"], title="b solo"),
    ])
    await session.flush()
    session.add_all([TaskAssignment(task_id=ids["shared"], user_id=ids["a"]),
                     TaskAssignment(task_id=ids["shared"], user_id=ids["b"]),
                     TaskAssignment(task_id=ids["solo"], user_id=ids["b"])])


async def _seed_personal_rows(session, ids):
    """One row of every kind, for B and for A (A's must survive)."""
    from app.models.badge import Badge
    from app.models.completion import TaskCompletion
    from app.models.module_guide_view import ModuleGuideView
    from app.models.objective_proposition import ObjectiveProposition, ObjectivePropositionResponse
    from app.models.task_affinity import TaskAffinity

    later = OLD + timedelta(days=400)
    for key in ("a", "b"):
        session.add_all([
            TaskCompletion(task_id=ids["shared"], completed_by=ids[key], completed_at=OLD,
                           notes=f"note of {key}"),
            TaskAffinity(task_id=ids["shared"], user_id=ids[key], score=7),
            Badge(foyer_id=ids["foyer"], user_id=ids[key], badge_type="first_task"),
            ModuleGuideView(user_id=ids[key], module_key="fair_share"),
        ])
    session.add_all([
        ObjectiveProposition(id=ids["prop_a"], foyer_id=ids["foyer"], proposer_id=ids["a"],
                             slider_values_json={}, status="expired", expires_at=later),
        ObjectiveProposition(id=ids["prop_b"], foyer_id=ids["foyer"], proposer_id=ids["b"],
                             slider_values_json={}, status="expired", expires_at=later),
    ])
    await session.flush()
    session.add_all([
        ObjectivePropositionResponse(proposition_id=ids["prop_a"], user_id=ids["b"], accepted=True),
        ObjectivePropositionResponse(proposition_id=ids["prop_b"], user_id=ids["a"], accepted=False),
    ])


@pytest_asyncio.fixture
async def household():
    """A (stays) and B (leaves) in one household, each owning one row of every personal kind."""
    from app.database import async_session
    from app.utils.changelog import record_change

    keys = ("foyer", "a", "b", "shared", "solo", "prop_a", "prop_b")
    ids = {k: uuid.uuid4() for k in keys}
    async with async_session() as session:
        await _seed_people(session, ids)
        await _seed_personal_rows(session, ids)
        for key in ("a", "b"):
            await record_change(session, "tasks", ids["shared"], "UPDATE", str(ids[key]),
                                foyer_id=ids["foyer"], new_data={"title": f"by {key}"})
        await session.commit()
    return ids


async def _b_leaves_long_ago(client, ids):
    from app.database import async_session
    from app.models.user import User
    from app.models.user_foyer import UserFoyer

    r = await client.delete("/api/me", headers=_headers(ids["b"], ids["foyer"]))
    assert r.status_code == 204, r.text
    async with async_session() as session:
        uf = (await session.execute(select(UserFoyer).where(UserFoyer.user_id == ids["b"]))).scalar_one()
        uf.left_at = OLD
        (await session.get(User, ids["b"])).deleted_at = OLD
        await session.commit()


async def _anonymize():
    from app.database import async_session
    from app.services.member_departure import anonymize_departed_users

    async with async_session() as session:
        assert await anonymize_departed_users(session) >= 1
        await session.commit()


async def _later_household_write(ids):
    """Someone, anywhere, writes after B's departure: B's rows are no longer the newest."""
    from app.database import async_session
    from app.utils.changelog import record_change

    async with async_session() as session:
        await record_change(session, "tasks", ids["shared"], "UPDATE", str(ids["a"]),
                            foyer_id=ids["foyer"], new_data={"title": "after"})
        await session.commit()


async def _rows_of(user_id) -> dict[str, int]:
    """Count, per table, the rows that name this user."""
    from app.database import async_session
    from app.services.account_erasure import PERSONAL_TABLES

    async with async_session() as session:
        counts = {}
        for model, column in PERSONAL_TABLES:
            counts[model.__tablename__] = (await session.execute(
                select(func.count()).select_from(model).where(column == user_id))).scalar_one()
        counts["change_log"] = (await session.execute(text(
            "SELECT count(*) FROM change_log WHERE changed_by = :u"), {"u": user_id})).scalar_one()
        return counts


async def _completions_and_notes(user_id) -> list[tuple]:
    from app.database import async_session
    from app.models.completion import TaskCompletion

    async with async_session() as session:
        return sorted((await session.execute(
            select(TaskCompletion.task_id, TaskCompletion.notes).where(
                TaskCompletion.completed_by == user_id))).all(), key=str)


async def _ssids(user_id) -> str:
    from app.database import async_session
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        return (await session.execute(select(UserFoyer.home_ssids).where(
            UserFoyer.user_id == user_id))).scalar_one()


async def _objective_prefs(user_id) -> list[tuple]:
    from app.database import async_session
    from app.models.user_foyer import UserFoyer

    async with async_session() as session:
        return list((await session.execute(select(
            UserFoyer.daily_objective_minutes, UserFoyer.objective_display_unit,
        ).where(UserFoyer.user_id == user_id))).all())


async def _stats_of_a(client, ids) -> list[dict]:
    r = await client.get("/api/stats", headers=_headers(ids["a"], ids["foyer"]))
    assert r.status_code == 200, r.text
    return [s for s in r.json() if s["user_id"] == str(ids["a"])]


@pytest.mark.asyncio
async def test_anonymization_erases_b_and_spares_a(client, household):
    ids = household
    stats_before = await _stats_of_a(client, ids)
    a_rows_before = await _rows_of(ids["a"])
    a_completions_before = await _completions_and_notes(ids["a"])

    await _b_leaves_long_ago(client, ids)
    await _later_household_write(ids)
    await _anonymize()

    assert {table: n for table, n in (await _rows_of(ids["b"])).items() if n} == {}
    # B's completion on the shared task stays, pseudonymized and without its note.
    assert await _completions_and_notes(ids["b"]) == [(ids["shared"], None)]
    assert await _ssids(ids["b"]) == ""
    assert await _objective_prefs(ids["b"]) == [(None, "min")]
    assert await _objective_prefs(ids["a"]) == [(20, "tasks")]

    # A's answer to B's proposal goes with the proposal (ON DELETE CASCADE): nothing left to answer.
    assert await _rows_of(ids["a"]) == {
        **a_rows_before, "change_log": a_rows_before["change_log"] + 1,
        "objective_proposition_responses": a_rows_before["objective_proposition_responses"] - 1,
    }
    assert await _completions_and_notes(ids["a"]) == a_completions_before
    assert await _ssids(ids["a"]) == "BoxA"
    stats_after = await _stats_of_a(client, ids)
    assert len(stats_after) == 1 and stats_after == stats_before
    # (7) B's users row stays: the household still holds B's completion.
    from app.database import async_session
    from app.models.user import User
    async with async_session() as session:
        assert (await session.get(User, ids["b"])).anonymized_at is not None


@pytest.mark.asyncio
async def test_solo_task_with_a_completion_does_not_block(client, household):
    from app.database import async_session
    from app.models.completion import TaskCompletion
    from app.models.task import Task

    from app.models.task_assignment import TaskAssignment

    ids = household
    also_done_by_a = uuid.uuid4()
    async with async_session() as session:
        session.add(Task(id=also_done_by_a, foyer_id=ids["foyer"], title="b solo, done by a"))
        await session.flush()
        session.add_all([
            TaskAssignment(task_id=also_done_by_a, user_id=ids["b"]),
            TaskCompletion(task_id=ids["solo"], completed_by=ids["b"], completed_at=OLD),
            TaskCompletion(task_id=also_done_by_a, completed_by=ids["a"], completed_at=OLD),
        ])
        await session.commit()
    a_before = await _completions_and_notes(ids["a"])
    await _b_leaves_long_ago(client, ids)
    await _anonymize()

    async with async_session() as session:
        assert await session.get(Task, ids["solo"]) is None
        # Another member's completion is never deleted: that task stays, suspended.
        assert await session.get(Task, also_done_by_a) is not None
    assert await _completions_and_notes(ids["b"]) == [(ids["shared"], None)]
    assert await _completions_and_notes(ids["a"]) == a_before


@pytest.mark.asyncio
async def test_newest_change_log_row_is_kept_unsigned(client, household):
    from app.database import async_session
    from app.utils.changelog import record_change

    ids = household
    await _b_leaves_long_ago(client, ids)
    async with async_session() as session:
        await record_change(session, "tasks", ids["shared"], "UPDATE", str(ids["b"]),
                            foyer_id=ids["foyer"], new_data={"title": "newest, by b"})
        await session.commit()
        newest = (await session.execute(text("SELECT max(server_version) FROM change_log"))).scalar()
    await _anonymize()

    async with async_session() as session:
        row = (await session.execute(text(
            "SELECT changed_by FROM change_log WHERE server_version = :v"), {"v": newest})).one()
        assert (await session.execute(text("SELECT max(server_version) FROM change_log"))).scalar() == newest
    assert row.changed_by is None
    assert (await _rows_of(ids["b"]))["change_log"] == 0


@pytest.mark.asyncio
async def test_leaving_empties_the_leavers_ssid_only(client, household):
    ids = household
    r = await client.post(f"/api/foyers/{ids['foyer']}/leave", headers=_headers(ids["b"], ids["foyer"]))
    assert r.status_code == 200, r.text
    assert await _ssids(ids["b"]) == ""
    assert await _ssids(ids["a"]) == "BoxA"


@pytest.mark.asyncio
async def test_settings_update_keeps_ssid_out_of_change_log(client, household):
    from app.database import async_session

    ids = household
    headers = _headers(ids["a"], ids["foyer"])
    r = await client.put("/api/settings", headers=headers, json={"home_ssids": ["SecretNet"]})
    assert r.status_code == 200 and r.json()["home_ssids"] == ["SecretNet"], r.text
    r = await client.put("/api/settings", headers=headers,
                         json={"home_ssids": ["SecretNet", "Other"], "notif_hour": 9})
    assert r.status_code == 200, r.text

    async with async_session() as session:
        logged = (await session.execute(text(
            "SELECT new_data FROM change_log WHERE table_name = 'user_settings' AND changed_by = :u"),
            {"u": ids["a"]})).scalars().all()
    assert logged == [{"notif_hour": 9}]


@pytest.mark.asyncio
async def test_migration_strips_ssid_copies_already_written(household):
    from app.database import async_session
    from app.utils.changelog import record_change

    ids = household
    spec = importlib.util.spec_from_file_location("ssid_migration", MIGRATION)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    async with async_session() as session:
        await record_change(session, "user_settings", ids["a"], "UPDATE", str(ids["a"]),
                            foyer_id=ids["foyer"], old_data={"home_ssids": ["Old"]},
                            new_data={"home_ssids": ["BoxA"], "notif_hour": 7})
        await session.execute(text(
            "UPDATE user_foyers SET left_at = :t WHERE user_id = :u"), {"t": OLD, "u": ids["b"]})
        await session.commit()
        for _ in range(2):
            for statement in migration.STATEMENTS:
                await session.execute(text(statement))
            await session.commit()
        row = (await session.execute(text(
            "SELECT old_data, new_data FROM change_log WHERE table_name = 'user_settings' "
            "AND changed_by = :u"), {"u": ids["a"]})).one()
        left = (await session.execute(text(
            "SELECT count(*) FROM change_log WHERE new_data ? 'home_ssids' OR old_data ? 'home_ssids'")
        )).scalar_one()
    assert row.old_data == {} and row.new_data == {"notif_hour": 7}
    assert left == 0
    assert await _ssids(ids["b"]) == "" and await _ssids(ids["a"]) == "BoxA"


@pytest.mark.asyncio
async def test_users_row_goes_with_the_last_household(client, household):
    from app.database import async_session
    from app.models.foyer import Foyer
    from app.models.user import User
    from app.models.user_foyer import UserFoyer
    from app.services.account_erasure import delete_unreferenced_anonymized_users
    from app.services.cleanup import purge_old_foyers

    from app.models.completion import TaskCompletion
    from app.models.task import Task
    from app.utils.changelog import record_change

    ids = household
    await _b_leaves_long_ago(client, ids)
    async with async_session() as session:
        # What anonymization leaves in the household: a task with no assignee (not deleted with
        # anyone) done by A, and an unsigned change_log row. Both used to block the purge.
        unassigned = uuid.uuid4()
        session.add(Task(id=unassigned, foyer_id=ids["foyer"], title="nobody's"))
        await session.flush()
        session.add(TaskCompletion(task_id=unassigned, completed_by=ids["a"], completed_at=OLD))
        await record_change(session, "tasks", unassigned, "UPDATE", None, foyer_id=ids["foyer"],
                            new_data={"archived": True})
        # A leaves too and the empty household reaches its purge date.
        (await session.execute(select(UserFoyer).where(UserFoyer.user_id == ids["a"]))).scalar_one() \
            .left_at = OLD
        (await session.get(Foyer, ids["foyer"])).archived_at = OLD
        await session.commit()
    await _later_household_write_elsewhere()
    await _anonymize()

    async with async_session() as session:
        assert await purge_old_foyers(session) >= 1
        assert await delete_unreferenced_anonymized_users(session) >= 2
        await session.commit()
        assert await session.get(Foyer, ids["foyer"]) is None
        assert await session.get(User, ids["b"]) is None
        assert await session.get(User, ids["a"]) is None


async def _later_household_write_elsewhere():
    """A write in the suite's own household, so the purged one does not hold the newest row."""
    from app.database import async_session
    from app.utils.changelog import record_change
    from tests.conftest import TEST_FOYER_ID, TEST_USER_ID

    async with async_session() as session:
        await record_change(session, "foyers", uuid.UUID(TEST_FOYER_ID), "UPDATE", TEST_USER_ID,
                            foyer_id=TEST_FOYER_ID, new_data={"note": "erasure test"})
        await session.commit()
