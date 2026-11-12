"""The catalogue import, called exactly as the app calls it since 2026-11-12.

`CreateFoyerWizardViewModel` sent the context keys as `room_keys` and read `rooms` back; it now
sends `context_keys` and `context_decisions` and reads `contexts`. The v0.37 names stay accepted
for the APKs in circulation (todo : « Renommer les champs d'API qui parlent encore de pièces »),
so a test that sends them proves nothing about the new client.

PATHS by which « the app's import attaches the chosen contexts » can break:
  (1) /resolve not returning the chosen contexts under `contexts` ......... COVERED
  (2) /apply ignoring `context_keys` / `context_decisions` ................ COVERED
  non couvert : the old names (`room_keys`), covered by test_contexts.py until they are retired.
  non couvert : the wizard's second call site (join flow), same request built the same way.
Negative witness, 2026-11-12: the same body with the keys renamed (`xcontext_keys`) fails (2).
"""

from __future__ import annotations

import uuid as uuid_lib

import pytest
from sqlalchemy import delete, select

from app.api.catalog import _implicit_keys
from app.services.catalog import get_catalog


@pytest.mark.asyncio
async def test_the_wizard_import_with_the_current_names(client, auth_headers):
    from app.database import async_session
    from app.models.context import Context, TaskContext
    from app.models.task import Task
    from tests.conftest import TEST_FOYER_ID

    catalog = get_catalog()
    implicites = _implicit_keys(catalog)
    # A task with at least two SELECTABLE contexts, distinct from test_contexts' own.
    candidates = [t for t in catalog.tasks if len([k for k in t.contexts if k not in implicites]) >= 2]
    task_tpl = candidates[-1]
    chosen = [k for k in task_tpl.contexts if k not in implicites]

    foyer = uuid_lib.UUID(TEST_FOYER_ID)
    async with async_session() as session:  # the dev base is not purged between runs
        for old in (await session.execute(
            select(Task).where(Task.foyer_id == foyer, Task.template_key == task_tpl.key)
        )).scalars().all():
            await session.execute(delete(TaskContext).where(TaskContext.task_id == old.id))
            await session.delete(old)
        await session.commit()

    resolved = await client.post(
        "/api/catalog/import/resolve", headers=auth_headers,
        json={"task_keys": [task_tpl.key], "context_keys": chosen},
    )
    assert resolved.status_code == 200, resolved.text
    contexts = resolved.json()["contexts"]
    assert set(chosen) <= {c["template_key"] for c in contexts}, contexts

    applied = await client.post(
        "/api/catalog/import/apply", headers=auth_headers,
        json={
            "task_keys": [task_tpl.key],
            "context_keys": chosen,
            "context_decisions": [
                {"template_key": c["template_key"], "decision": "create", "map_to_id": None}
                for c in contexts
            ],
        },
    )
    assert applied.status_code in (200, 201), applied.text

    async with async_session() as session:
        task = (await session.execute(
            select(Task).where(Task.foyer_id == foyer, Task.template_key == task_tpl.key)
        )).scalars().first()
        assert task is not None, "the task was not created"
        links = (await session.execute(
            select(TaskContext).where(TaskContext.task_id == task.id)
        )).scalars().all()
        keys = {(await session.get(Context, link.context_id)).template_key for link in links}
    assert set(chosen) <= keys, f"chosen contexts not attached: {sorted(set(chosen) - keys)}"
