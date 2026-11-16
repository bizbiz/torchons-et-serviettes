"""Response of GET /api/stats/category-frequency: completions per context, per member.

Each entry is the task's PRIMARY CONTEXT. The route kept its old path, but its body speaks of
contexts only (`contexts`, `context_id`, `context_name`): the old names (`categories`,
`category_id`, `category_name`) were retired on 2026-11-16, behind the version gate (112).
"""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel


class ContextFrequencyEntry(BaseModel):
    context_id: UUID | None  # None = no context
    context_name: str
    counts: list[int]  # parallel to `users` (same order)
    total: int


class CategoryFrequencyUser(BaseModel):
    user_id: UUID
    display_name: str


class CategoryFrequencyResponse(BaseModel):
    users: list[CategoryFrequencyUser]
    contexts: list[ContextFrequencyEntry]  # top 8, sorted by total desc
