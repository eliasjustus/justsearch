"""Tests for the provider-agnostic external-LLM-grader HTTP client (tempdoc 624
§M.9 "U-Founder-4 revised" — cross-family grader panel replaces bulk human
labeling).

Every HTTP call in this file is mocked (`monkeypatch.setattr(eg.httpx, "post",
...)`, matching the convention already used for `_judge_once`'s own tests in
`test_utility_judge.py`) -- NO real network call is ever made by this test
module, and no real vendor endpoint/model/API-key appears anywhere here.
"""

from __future__ import annotations

import pytest

from jseval import external_grader as eg


def _fake_post_factory(rescue_token: str = "RESCUE"):
    """A fake `httpx.post` that says YES iff `rescue_token` appears in the user
    message -- mirrors `test_utility_judge._fake_judge_post`'s shape."""

    class _Resp:
        def __init__(self, content: str):
            self._content = content

        def raise_for_status(self):
            pass

        def json(self):
            return {"choices": [{"message": {"content": self._content}}]}

    def _post(url, json=None, headers=None, timeout=None):
        user = json["messages"][1]["content"]
        return _Resp("YES" if rescue_token in user else "NO")

    return _post


# --- GraderConfig -------------------------------------------------------------


class TestGraderConfig:
    def test_no_hardcoded_endpoint_or_model(self):
        # A bare config with only the required fields must not silently resolve
        # to some real vendor default -- caller-supplied, not baked in.
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid/v1/chat",
                               model="some-model")
        assert cfg.endpoint_url == "http://example.invalid/v1/chat"
        assert cfg.model == "some-model"
        assert cfg.headers == {}

    def test_is_frozen(self):
        cfg = eg.GraderConfig(name="g", endpoint_url="http://x", model="m")
        with pytest.raises(Exception):
            cfg.name = "other"  # dataclass(frozen=True) -- must reject mutation


# --- GraderCallBudget -----------------------------------------------------


class TestGraderCallBudget:
    def test_rejects_non_positive_cap(self):
        with pytest.raises(ValueError):
            eg.GraderCallBudget(0)
        with pytest.raises(ValueError):
            eg.GraderCallBudget(-1)

    def test_consume_under_cap_succeeds(self):
        b = eg.GraderCallBudget(3)
        b.consume(1)
        b.consume(2)
        assert b.calls_made == 3

    def test_consume_over_cap_raises_before_mutating(self):
        b = eg.GraderCallBudget(2)
        b.consume(1)
        with pytest.raises(eg.GraderCallBudgetExceeded):
            b.consume(2)
        # the failed consume() must not have partially applied
        assert b.calls_made == 1


# --- call_grader_once / call_grader_dual_order --------------------------------


class TestCallGraderOnce:
    def test_yes_no_parsing(self, monkeypatch):
        monkeypatch.setattr(eg.httpx, "post", _fake_post_factory())
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid", model="m")
        assert eg.call_grader_once(cfg, "Q", "ref", "yellow RESCUE fruit", ref_first=True) is True
        assert eg.call_grader_once(cfg, "Q", "ref", "totally wrong", ref_first=True) is False

    def test_ref_first_and_candidate_first_produce_different_bodies(self, monkeypatch):
        seen = []

        def _post(url, json=None, headers=None, timeout=None):
            seen.append(json["messages"][1]["content"])

            class _R:
                def raise_for_status(self):
                    pass

                def json(self):
                    return {"choices": [{"message": {"content": "NO"}}]}
            return _R()

        monkeypatch.setattr(eg.httpx, "post", _post)
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid", model="m")
        eg.call_grader_once(cfg, "Q", "REF", "CAND", ref_first=True)
        eg.call_grader_once(cfg, "Q", "REF", "CAND", ref_first=False)
        assert seen[0].index("REF") < seen[0].index("CAND")
        assert seen[1].index("CAND") < seen[1].index("REF")

    def test_uses_config_headers_and_timeout(self, monkeypatch):
        captured = {}

        def _post(url, json=None, headers=None, timeout=None):
            captured["headers"] = headers
            captured["timeout"] = timeout
            captured["url"] = url

            class _R:
                def raise_for_status(self):
                    pass

                def json(self):
                    return {"choices": [{"message": {"content": "YES"}}]}
            return _R()

        monkeypatch.setattr(eg.httpx, "post", _post)
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid/chat", model="m",
                               headers={"Authorization": "Bearer secret"}, timeout_sec=12.5)
        eg.call_grader_once(cfg, "Q", "r", "c", ref_first=True)
        assert captured["headers"] == {"Authorization": "Bearer secret"}
        assert captured["timeout"] == 12.5
        assert captured["url"] == "http://example.invalid/chat"

    def test_consumes_budget(self, monkeypatch):
        monkeypatch.setattr(eg.httpx, "post", _fake_post_factory())
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid", model="m")
        budget = eg.GraderCallBudget(1)
        eg.call_grader_once(cfg, "Q", "r", "c", ref_first=True, budget=budget)
        assert budget.calls_made == 1
        with pytest.raises(eg.GraderCallBudgetExceeded):
            eg.call_grader_once(cfg, "Q", "r", "c", ref_first=False, budget=budget)

    def test_budget_exceeded_prevents_the_http_call(self, monkeypatch):
        # The call must never reach httpx.post once the budget is exhausted --
        # not just raise after making the request.
        calls = []
        monkeypatch.setattr(eg.httpx, "post", lambda *a, **k: calls.append(1))
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid", model="m")
        budget = eg.GraderCallBudget(1)
        budget.consume(1)  # pre-exhaust
        with pytest.raises(eg.GraderCallBudgetExceeded):
            eg.call_grader_once(cfg, "Q", "r", "c", ref_first=True, budget=budget)
        assert calls == []


class TestCallGraderDualOrder:
    def test_agreement_returns_the_shared_verdict(self, monkeypatch):
        monkeypatch.setattr(eg.httpx, "post", _fake_post_factory())
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid", model="m")
        # Both orders see "RESCUE" in the user message -> both YES -> agreement.
        result = eg.call_grader_dual_order(cfg, "Q", "RESCUE ref", "RESCUE cand")
        assert result is True

    def test_disagreement_abstains_to_none(self, monkeypatch):
        # ref-first message contains "RESCUE" (from the reference text) -> YES;
        # candidate-first message's user text also contains "RESCUE" from the
        # same reference text placed after the candidate -- so to force a genuine
        # split we make the fake keyed on ORDER instead.
        order_seen = []

        def _post(url, json=None, headers=None, timeout=None):
            user = json["messages"][1]["content"]
            ref_first = user.index("REFERENCE") < user.index("CANDIDATE")
            order_seen.append(ref_first)

            class _R:
                def raise_for_status(self):
                    pass

                def json(self):
                    return {"choices": [{"message": {"content": "YES" if ref_first else "NO"}}]}
            return _R()

        monkeypatch.setattr(eg.httpx, "post", _post)
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid", model="m")
        result = eg.call_grader_dual_order(cfg, "Q", "ref", "cand")
        assert result is None
        assert order_seen == [True, False]

    def test_dual_order_consumes_two_budget_units(self, monkeypatch):
        monkeypatch.setattr(eg.httpx, "post", _fake_post_factory())
        cfg = eg.GraderConfig(name="g", endpoint_url="http://example.invalid", model="m")
        budget = eg.GraderCallBudget(2)
        eg.call_grader_dual_order(cfg, "Q", "r", "c", budget=budget)
        assert budget.calls_made == 2


# --- estimate_cross_family_cost ------------------------------------------------


class TestEstimateCrossFamilyCost:
    def test_basic_two_grader_dual_order(self):
        graders = [eg.GraderConfig(name="gpt-class", endpoint_url="http://a", model="m1"),
                   eg.GraderConfig(name="gemini-class", endpoint_url="http://b", model="m2")]
        price_table = {"gpt-class": 0.01, "gemini-class": 0.02}
        result = eg.estimate_cross_family_cost(40, graders, price_table)
        # 40 samples * 2 (dual-order) * 2 graders = 160 calls
        assert result["call_count"] == 160
        assert result["n_samples"] == 40
        assert result["n_graders"] == 2
        assert result["dual_order"] is True
        # gpt-class: 40*2*0.01 = 0.8 ; gemini-class: 40*2*0.02 = 1.6
        assert result["per_grader"]["gpt-class"] == pytest.approx(0.8)
        assert result["per_grader"]["gemini-class"] == pytest.approx(1.6)
        assert result["cost_estimate_usd"] == pytest.approx(2.4)

    def test_single_order_halves_call_count(self):
        graders = [eg.GraderConfig(name="a", endpoint_url="http://a", model="m1"),
                   eg.GraderConfig(name="b", endpoint_url="http://b", model="m2")]
        price_table = {"a": 0.01, "b": 0.01}
        dual = eg.estimate_cross_family_cost(10, graders, price_table, dual_order=True)
        single = eg.estimate_cross_family_cost(10, graders, price_table, dual_order=False)
        assert dual["call_count"] == 2 * single["call_count"]
        assert dual["cost_estimate_usd"] == pytest.approx(2 * single["cost_estimate_usd"])

    def test_scales_linearly_with_sample_size(self):
        graders = [eg.GraderConfig(name="a", endpoint_url="http://a", model="m1"),
                   eg.GraderConfig(name="b", endpoint_url="http://b", model="m2")]
        price_table = {"a": 0.005, "b": 0.005}
        small = eg.estimate_cross_family_cost(10, graders, price_table)
        big = eg.estimate_cross_family_cost(50, graders, price_table)
        assert big["call_count"] == 5 * small["call_count"]
        assert big["cost_estimate_usd"] == pytest.approx(5 * small["cost_estimate_usd"])

    def test_three_graders(self):
        graders = [eg.GraderConfig(name=n, endpoint_url="http://x", model="m")
                   for n in ("a", "b", "c")]
        price_table = {"a": 0.01, "b": 0.02, "c": 0.03}
        result = eg.estimate_cross_family_cost(5, graders, price_table)
        assert result["call_count"] == 5 * 2 * 3
        assert result["cost_estimate_usd"] == pytest.approx(5 * 2 * (0.01 + 0.02 + 0.03))

    def test_missing_price_raises_keyerror_not_silent_zero(self):
        graders = [eg.GraderConfig(name="unpriced", endpoint_url="http://x", model="m")]
        with pytest.raises(KeyError):
            eg.estimate_cross_family_cost(10, graders, {})

    def test_pure_no_network_call(self, monkeypatch):
        def _boom(*a, **k):
            raise AssertionError("estimate_cross_family_cost must never call the network")
        monkeypatch.setattr(eg.httpx, "post", _boom)
        graders = [eg.GraderConfig(name="a", endpoint_url="http://a", model="m1"),
                   eg.GraderConfig(name="b", endpoint_url="http://b", model="m2")]
        eg.estimate_cross_family_cost(40, graders, {"a": 0.01, "b": 0.01})
