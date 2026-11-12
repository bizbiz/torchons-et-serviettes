"""Response of GET /api/stats/category-frequency: completions per context, per member.

The route predates the contexts: its body speaks of CATEGORIES (`categories`, `category_id`,
`category_name`), which now carry the task's PRIMARY CONTEXT. Since 2026-11-12 the same list is
also served under the current names (`contexts`, `context_id`, `context_name`), which the app
reads. The old names stay while an APK that reads them is in circulation (todo : « Renommer les
champs d'API qui parlent encore de pièces »).
"""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, computed_field


class CategoryFrequencyEntry(BaseModel):
    category_id: UUID | None  # None = sans contexte
    category_name: str
    counts: list[int]  # parallele a `users` (meme ordre)
    total: int


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
    categories: list[CategoryFrequencyEntry]  # top 8, triees par total desc

    @computed_field
    @property
    def contexts(self) -> list[ContextFrequencyEntry]:
        """`categories` under the current names, same order."""
        return [
            ContextFrequencyEntry(
                context_id=c.category_id, context_name=c.category_name, counts=c.counts, total=c.total,
            )
            for c in self.categories
        ]
