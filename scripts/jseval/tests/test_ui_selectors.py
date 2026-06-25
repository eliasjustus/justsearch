"""Tests for the self-healing Selector primitive (tempdoc 615 §11 HARDEN).

The failure mode is a brittle step: a testid rename breaks resolution even though the
element is unchanged by accessible role+name. These pin the resolution ORDER (role+name
wins when present; testid is the fallback) without a browser.
"""
from __future__ import annotations

import asyncio

from jseval.ui_selectors import Selector


class _FakeLocator:
    def __init__(self, count: int, tag: str, raises: bool = False):
        self._count = count
        self.tag = tag
        self._raises = raises
        self.first = self

    async def count(self) -> int:
        if self._raises:
            raise RuntimeError("count failed")
        return self._count


class _FakePage:
    def __init__(self, role_count: int, role_raises: bool = False):
        self._role_count = role_count
        self._role_raises = role_raises
        self.calls: list = []

    def get_by_role(self, role, name=None):
        self.calls.append(("role", role, name))
        return _FakeLocator(self._role_count, "role", self._role_raises)

    def get_by_test_id(self, tid):
        self.calls.append(("testid", tid))
        return _FakeLocator(1, "testid")


def _locate(sel, page):
    return asyncio.run(sel.locate(page))


def test_role_match_wins():
    page = _FakePage(role_count=1)
    loc = _locate(Selector("searchbox", "Search files", "search-input"), page)
    assert loc.tag == "role"


def test_falls_back_to_testid_when_role_absent():
    page = _FakePage(role_count=0)
    loc = _locate(Selector("searchbox", "Search files", "search-input"), page)
    assert loc.tag == "testid"
    assert ("testid", "search-input") in page.calls


def test_role_error_falls_back_to_testid():
    page = _FakePage(role_count=0, role_raises=True)
    loc = _locate(Selector("searchbox", "Search files", "search-input"), page)
    assert loc.tag == "testid"


def test_no_testid_returns_role_locator():
    # No testid fallback → return the (possibly empty) role locator, never None.
    page = _FakePage(role_count=0)
    loc = _locate(Selector("searchbox", "Search files", testid=None), page)
    assert loc.tag == "role"
