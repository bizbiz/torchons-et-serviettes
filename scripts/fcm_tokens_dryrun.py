"""Dry-run every FCM token of the database: nothing is delivered, no phone rings.

FCM validates each token as for a real send, so a dead one answers UnregisteredError.
Prints alive/dead counts per member, never a token. With --delete, removes the dead
tokens, and only those: a write, so in PROD only with explicit approval and after
a prod dump.

Runs inside the API container, which holds firebase-key.json (the beta has none):

    scp scripts/fcm_tokens_dryrun.py <host>:<mktemp -d dir>/
    docker cp <dir>/fcm_tokens_dryrun.py ts_api_prod:/tmp/
    docker exec -w /app ts_api_prod python -P -W ignore /tmp/fcm_tokens_dryrun.py [--delete]

First run 2026-10-19 in prod: 9 tokens, 3 dead (all Quentin's), deleted with --delete.
"""
import asyncio
import sys
from collections import Counter

sys.path.insert(0, "/app")

import firebase_admin  # noqa: E402
from firebase_admin import messaging  # noqa: E402
from sqlalchemy import delete, select  # noqa: E402

import app.services.push  # noqa: E402,F401  (initialises firebase_admin)
from app.database import async_session  # noqa: E402
from app.models.notification import NotificationToken  # noqa: E402
from app.models.user import User  # noqa: E402

DELETE = "--delete" in sys.argv


async def main():
    assert firebase_admin._apps, "firebase not initialised (no firebase-key.json?)"
    async with async_session() as session:
        rows = (await session.execute(
            select(NotificationToken.fcm_token, User.display_name)
            .join(User, User.id == NotificationToken.user_id)
        )).all()
        if not rows:
            print("total=0 : no token in the database, nothing measured")
            return
        resp = messaging.send_each_for_multicast(
            messaging.MulticastMessage(tokens=[r[0] for r in rows], data={"type": "probe"}),
            dry_run=True)
        tally, dead = Counter(), []
        for (tok, name), r in zip(rows, resp.responses):
            if r.exception is None:
                state = "alive"
            elif isinstance(r.exception, messaging.UnregisteredError):
                state = "dead"
                dead.append(tok)
            else:
                state = "other:" + type(r.exception).__name__
            tally[(name, state)] += 1
        for (name, state), n in sorted(tally.items()):
            print(f"{name}: {state} = {n}")
        print(f"total={len(rows)} dead={len(dead)}")
        if DELETE and dead:
            res = await session.execute(
                delete(NotificationToken).where(NotificationToken.fcm_token.in_(dead)))
            await session.commit()
            print(f"deleted={res.rowcount}")


asyncio.run(main())
