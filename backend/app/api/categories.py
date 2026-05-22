"""Routes CRUD categories."""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import get_current_user
from app.database import get_session
from app.models.category import Category
from app.models.task import Task
from app.utils.errors import LocalizedHTTPException
from app.schemas.category import CategoryCreate, CategoryResponse, CategoryUpdate
from app.utils.changelog import record_change
from app.utils.scoping import current_foyer_uuid, scoped

router = APIRouter(prefix="/api/categories", tags=["categories"])


@router.get("", response_model=list[CategoryResponse])
async def list_categories(
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    # Tri alphabetique pour uniformite avec rooms, scope par foyer.
    result = await session.execute(
        scoped(Category, current).where(Category.deleted_at.is_(None)).order_by(Category.name)
    )
    return result.scalars().all()


@router.post("", response_model=CategoryResponse, status_code=201)
async def create_category(
    body: CategoryCreate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    cat = Category(
        foyer_id=current_foyer_uuid(current),
        name=body.name,
        color=body.color,
        icon=body.icon,
    )
    session.add(cat)
    await session.flush()
    await record_change(
        session, "categories", cat.id, "INSERT", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    await session.refresh(cat)
    return cat


@router.put("/{cat_id}", response_model=CategoryResponse)
async def update_category(
    cat_id: UUID,
    body: CategoryUpdate,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    result = await session.execute(
        scoped(Category, current).where(Category.id == cat_id, Category.deleted_at.is_(None))
    )
    cat = result.scalar_one_or_none()
    if not cat:
        raise LocalizedHTTPException(status_code=404, error_code="CATEGORY_NOT_FOUND")
    for key, val in body.model_dump(exclude_unset=True).items():
        setattr(cat, key, val)
    cat.version += 1
    await record_change(
        session, "categories", cat.id, "UPDATE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    await session.refresh(cat)
    return cat


@router.delete("/{cat_id}")
async def delete_category(
    cat_id: UUID,
    session: AsyncSession = Depends(get_session),
    current: dict = Depends(get_current_user),
):
    result = await session.execute(
        scoped(Category, current).where(Category.id == cat_id, Category.deleted_at.is_(None))
    )
    cat = result.scalar_one_or_none()
    if not cat:
        raise LocalizedHTTPException(status_code=404, error_code="CATEGORY_NOT_FOUND")
    # Deplacer les taches de cette categorie en "sans categorie" (scope foyer)
    from sqlalchemy import update
    result_tasks = await session.execute(
        update(Task)
        .where(Task.category_id == cat_id, Task.foyer_id == current_foyer_uuid(current))
        .values(category_id=None)
        .returning(Task.id)
    )
    orphaned_count = len(result_tasks.all())

    cat.deleted_at = datetime.now(timezone.utc)
    cat.version += 1
    await record_change(
        session, "categories", cat.id, "DELETE", current["user_id"],
        foyer_id=current["foyer_id"],
    )
    await session.commit()
    return {"ok": True, "orphaned_tasks": orphaned_count}
