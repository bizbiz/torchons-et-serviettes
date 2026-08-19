"""Module registry state — progressive disclosure (P1).

Revision ID: a1b2c3d4e5f7
Revises: f7a8b9c0d1e2
Create Date: 2026-08-17

Creates `module_states`, one row per enable/disable decision. A NULL `user_id`
carries a collective decision (whole foyer), a non-NULL one a personal decision
scoped to that member within that foyer. Postgres treats NULLs as distinct in a
unique index, hence two partial unique indexes rather than one constraint.

GRANDFATHERING — the reason this migration has a data step.

Every foyer that exists at switchover time gets an explicit row for all 8
modules, enabled. That is what "nothing changes for current users" means: the
module gate opens, and the fine-grained sub-options keep whatever value they
already had (home_freeze stays off if it was off, the WiFi masking stays on).

The single exception is `motivation`, which inherits `foyers.gamification_enabled`
— a foyer that deliberately turned gamification off must not have it switched
back on by a migration.

Absence of rows is therefore meaningful afterwards: a foyer with none was
created after the switchover, and falls back to `default_new` from modules.yaml
(core plus reminders only). No extra flag needed to tell the two populations
apart.

`user_settings.gamification_enabled` is NOT touched. It stops being a second
gate on a collective decision — the defect this work corrects — and becomes the
personal display preference `motivation.hide_for_me`. Same column, same values,
honest name.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'a1b2c3d4e5f7'
down_revision: Union[str, None] = 'f7a8b9c0d1e2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# Keep in sync with backend/app/content/modules.yaml. Duplicated on purpose:
# a migration must stay reproducible against the YAML as it was on the day it
# ran, not as it is today.
COLLECTIVE_MODULES = (
    'repartition',
    'motivation',
    'bonus_malus',
    'objectives',
    'presence',
    'auto_adjust',
)
PERSONAL_MODULES = (
    'statistics',
    'reminders',
)


def upgrade() -> None:
    op.create_table(
        'module_states',
        sa.Column('id', sa.dialects.postgresql.UUID(as_uuid=True),
                  primary_key=True,
                  server_default=sa.text('gen_random_uuid()')),
        sa.Column('foyer_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('foyers.id', ondelete='CASCADE'),
                  nullable=False),
        sa.Column('user_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('users.id', ondelete='CASCADE'),
                  nullable=True),
        sa.Column('module_key', sa.String(50), nullable=False),
        sa.Column('enabled', sa.Boolean(), nullable=False),
        sa.Column('enabled_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True),
                  server_default=sa.func.now(), nullable=False),
        sa.Column('updated_by_user_id', sa.dialects.postgresql.UUID(as_uuid=True),
                  sa.ForeignKey('users.id', ondelete='SET NULL'),
                  nullable=True),
    )
    op.create_index(
        'uq_module_states_collective', 'module_states',
        ['foyer_id', 'module_key'],
        unique=True, postgresql_where=sa.text('user_id IS NULL'),
    )
    op.create_index(
        'uq_module_states_personal', 'module_states',
        ['foyer_id', 'user_id', 'module_key'],
        unique=True, postgresql_where=sa.text('user_id IS NOT NULL'),
    )
    op.create_index('ix_module_states_foyer', 'module_states', ['foyer_id'])

    # --- Grandfathering ---

    for key in COLLECTIVE_MODULES:
        # motivation inherits the foyer's existing gamification choice; every
        # other collective module opens.
        enabled_expr = (
            'f.gamification_enabled' if key == 'motivation' else 'TRUE'
        )
        op.execute(sa.text(f"""
            INSERT INTO module_states
                (id, foyer_id, user_id, module_key, enabled, enabled_at, updated_at)
            SELECT gen_random_uuid(), f.id, NULL, :key,
                   {enabled_expr},
                   CASE WHEN {enabled_expr} THEN now() ELSE NULL END,
                   now()
            FROM foyers f
        """).bindparams(key=key))

    for key in PERSONAL_MODULES:
        # Personal modules are per (member, foyer). former_member rows are
        # excluded: re-joining goes through recovery, which will create the
        # state on demand rather than resurrect a stale one.
        op.execute(sa.text("""
            INSERT INTO module_states
                (id, foyer_id, user_id, module_key, enabled, enabled_at, updated_at)
            SELECT gen_random_uuid(), uf.foyer_id, uf.user_id, :key,
                   TRUE, now(), now()
            FROM user_foyers uf
            WHERE uf.role <> 'former_member'
        """).bindparams(key=key))


def downgrade() -> None:
    op.drop_index('ix_module_states_foyer', table_name='module_states')
    op.drop_index('uq_module_states_personal', table_name='module_states')
    op.drop_index('uq_module_states_collective', table_name='module_states')
    op.drop_table('module_states')
