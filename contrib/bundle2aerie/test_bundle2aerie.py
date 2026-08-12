#!/usr/bin/env python3
"""Unit tests for bundle2aerie.py.

Runnable via:
    python3 -m unittest test_bundle2aerie -v
or:
    pytest test_bundle2aerie.py -v

No live Aerie/Hasura instance is required or used -- see this directory's
README.md for why (there is none available). Every test here exercises pure
mapping functions or the DryRunClient, never real urllib.request calls. If
AERIE_URL is set in the environment, one additional smoke test tries a real
HTTP round trip against it and is otherwise skipped; this was never
exercised in development because no such instance was available.
"""
import copy
import os
import sys
import unittest
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import bundle2aerie as b2a  # noqa: E402


def make_bundle(**overrides):
    bundle = {
        "bundleVersion": "1.0.0",
        "plan": {"name": "test plan", "startTime": "2024-060T00:00:00.000000", "duration": "+02:00:00.000000"},
        "activityDirectives": [
            {"id": 1, "type": "Foo", "startOffset": "+00:00:00.000000", "arguments": {}},
        ],
        "simulation": {
            "simulationStartTime": "2024-060T00:00:00.000000",
            "simulationEndTime": "2024-060T02:00:00.000000",
            "spans": [],
            "resources": [],
        },
    }
    bundle.update(overrides)
    return bundle


class TestDurationParsing(unittest.TestCase):
    def test_aerie_signed_positive(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("+11:39:55.219000"), (11 * 3600 + 39 * 60 + 55) * 1_000_000 + 219000)

    def test_aerie_signed_negative(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("-00:00:05.000000"), -5_000_000)

    def test_aerie_signed_hours_not_rolled_into_days(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("+30:00:00.000000"), 30 * 3600 * 1_000_000)

    def test_postgres_interval_no_sign(self):
        self.assertEqual(
            b2a.parse_duration_to_microseconds("02:27:15.059"),
            (2 * 3600 + 27 * 60 + 15) * 1_000_000 + 59000,
        )

    def test_postgres_interval_no_fraction(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("01:00:00"), 3_600_000_000)

    def test_iso8601_duration(self):
        self.assertEqual(
            b2a.parse_duration_to_microseconds("PT2H27M15.059S"),
            (2 * 3600 + 27 * 60 + 15) * 1_000_000 + 59000,
        )

    def test_iso8601_duration_with_days(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("P1DT2H"), (24 + 2) * 3600 * 1_000_000)

    def test_iso8601_duration_negative(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("-PT30M"), -30 * 60 * 1_000_000)

    def test_iso8601_duration_minutes_only(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("PT45M"), 45 * 60 * 1_000_000)

    def test_integer_microseconds(self):
        self.assertEqual(b2a.parse_duration_to_microseconds(3600000000), 3600000000)

    def test_float_microseconds_rounds(self):
        self.assertEqual(b2a.parse_duration_to_microseconds(1000.4), 1000)
        self.assertEqual(b2a.parse_duration_to_microseconds(1000.6), 1001)

    def test_zero(self):
        self.assertEqual(b2a.parse_duration_to_microseconds("+00:00:00.000000"), 0)
        self.assertEqual(b2a.parse_duration_to_microseconds(0), 0)

    def test_invalid_string_raises(self):
        with self.assertRaises(ValueError):
            b2a.parse_duration_to_microseconds("not-a-duration")

    def test_bool_raises(self):
        with self.assertRaises(ValueError):
            b2a.parse_duration_to_microseconds(True)

    def test_microseconds_to_pg_interval_roundtrip(self):
        for s in ["+11:39:55.219000", "02:27:15.059000", "-00:00:05.000000"]:
            micros = b2a.parse_duration_to_microseconds(s)
            back = b2a.parse_duration_to_microseconds(b2a.microseconds_to_pg_interval(micros))
            self.assertEqual(micros, back)

    def test_microseconds_to_pg_interval_format(self):
        self.assertEqual(b2a.microseconds_to_pg_interval(3_661_500_000), "01:01:01.500000")
        self.assertEqual(b2a.microseconds_to_pg_interval(-5_000_000), "-00:00:05.000000")


class TestProfileSetMapping(unittest.TestCase):
    def test_discrete_resource_keyed_by_name(self):
        resources = [
            {
                "name": "/my_boolean",
                "type": "discrete",
                "schema": {"type": "boolean"},
                "segments": [{"extent": "01:00:00", "dynamics": False}],
            }
        ]
        profile_set = b2a.build_profile_set(resources)
        self.assertIn("/my_boolean", profile_set)
        self.assertEqual(profile_set["/my_boolean"]["type"], "discrete")
        self.assertEqual(profile_set["/my_boolean"]["schema"], {"type": "boolean"})

    def test_extent_converted_to_integer_microseconds_matching_e2e_fixture(self):
        # Same shape as ExternalDatasetsTest.myBooleanProfile in
        # e2e-tests: five 1-hour segments -> 3600000000 microseconds each.
        resources = [
            {
                "name": "/my_boolean",
                "type": "discrete",
                "schema": {"type": "boolean"},
                "segments": [
                    {"extent": "01:00:00", "dynamics": False},
                    {"extent": "01:00:00", "dynamics": None},
                    {"extent": "01:00:00", "dynamics": True},
                ],
            }
        ]
        segs = b2a.build_profile_set(resources)["/my_boolean"]["segments"]
        self.assertEqual([s["duration"] for s in segs], [3600000000] * 3)

    def test_extent_is_not_prefix_summed(self):
        resources = [
            {
                "name": "/r",
                "type": "discrete",
                "schema": {"type": "int"},
                "segments": [
                    {"extent": "01:00:00", "dynamics": 1},
                    {"extent": "01:00:00", "dynamics": 2},
                    {"extent": "01:00:00", "dynamics": 3},
                ],
            }
        ]
        segs = b2a.build_profile_set(resources)["/r"]["segments"]
        # Every segment's duration is the SAME delta (1 hour), never a
        # running total (which would be 3600e6, 7200e6, 10800e6).
        self.assertEqual([s["duration"] for s in segs], [3600000000, 3600000000, 3600000000])

    def test_null_dynamics_marks_gap(self):
        resources = [
            {
                "name": "/r",
                "type": "discrete",
                "schema": {"type": "int"},
                "segments": [{"extent": "01:00:00", "dynamics": None}],
            }
        ]
        segs = b2a.build_profile_set(resources)["/r"]["segments"]
        self.assertIsNone(segs[0]["dynamics"])

    def test_is_gap_flag_forces_null_dynamics(self):
        resources = [
            {
                "name": "/r",
                "type": "discrete",
                "schema": {"type": "int"},
                "segments": [{"extent": "01:00:00", "dynamics": 5, "isGap": True}],
            }
        ]
        segs = b2a.build_profile_set(resources)["/r"]["segments"]
        self.assertIsNone(segs[0]["dynamics"])

    def test_is_gap_false_with_present_dynamics_is_not_a_gap(self):
        resources = [
            {
                "name": "/r",
                "type": "discrete",
                "schema": {"type": "int"},
                "segments": [{"extent": "01:00:00", "dynamics": 5, "isGap": False}],
            }
        ]
        segs = b2a.build_profile_set(resources)["/r"]["segments"]
        self.assertEqual(segs[0]["dynamics"], 5)

    def test_real_resource_dynamics_passthrough(self):
        resources = [
            {
                "name": "/batterySoC",
                "type": "real",
                "schema": {"type": "struct", "items": {"initial": {"type": "real"}, "rate": {"type": "real"}}},
                "segments": [{"extent": "PT20M", "dynamics": {"initial": 100.0, "rate": -0.01}}],
            }
        ]
        profile_set = b2a.build_profile_set(resources)
        seg = profile_set["/batterySoC"]["segments"][0]
        self.assertEqual(seg["dynamics"], {"initial": 100.0, "rate": -0.01})
        self.assertEqual(seg["duration"], 20 * 60 * 1_000_000)

    def test_multiple_resources_each_get_own_key(self):
        resources = [
            {"name": "/a", "type": "discrete", "schema": {"type": "int"}, "segments": []},
            {"name": "/b", "type": "discrete", "schema": {"type": "int"}, "segments": []},
        ]
        profile_set = b2a.build_profile_set(resources)
        self.assertEqual(set(profile_set.keys()), {"/a", "/b"})

    def test_empty_segments_list_is_valid(self):
        resources = [{"name": "/empty", "type": "discrete", "schema": {"type": "int"}, "segments": []}]
        profile_set = b2a.build_profile_set(resources)
        self.assertEqual(profile_set["/empty"]["segments"], [])


class TestDatasetStartDerivation(unittest.TestCase):
    # merlin-server parses datasetStart itself and accepts ONLY the Aerie
    # day-of-year spelling. Verified against a live instance: an ISO-8601
    # value is rejected at the action boundary with
    #   {"breadcrumbs":["input","datasetStart"],"message":"invalid timestamp format"}
    # so every accepted bundle spelling must be normalized to day-of-year.
    def test_derives_from_simulation_start_time(self):
        bundle = make_bundle()
        self.assertEqual(b2a.derive_dataset_start(bundle), "2024-060T00:00:00.000")

    def test_normalizes_iso_form_to_day_of_year(self):
        bundle = make_bundle(simulation={
            "simulationStartTime": "2024-07-01T00:00:00Z",
            "simulationEndTime": "2024-07-01T02:00:00Z",
            "spans": [],
            "resources": [],
        })
        # 2024 is a leap year, so July 1st is day 183.
        self.assertEqual(b2a.derive_dataset_start(bundle), "2024-183T00:00:00.000")

    def test_normalizes_iso_with_offset_to_day_of_year(self):
        bundle = make_bundle(simulation={
            "simulationStartTime": "2024-07-01T00:00:00+00:00",
            "simulationEndTime": "2024-07-01T02:00:00+00:00",
            "spans": [],
            "resources": [],
        })
        self.assertEqual(b2a.derive_dataset_start(bundle), "2024-183T00:00:00.000")

    def test_preserves_time_of_day_and_milliseconds(self):
        bundle = make_bundle(simulation={
            "simulationStartTime": "2024-07-01T08:30:45.250Z",
            "simulationEndTime": "2024-07-01T10:00:00Z",
            "spans": [],
            "resources": [],
        })
        self.assertEqual(b2a.derive_dataset_start(bundle), "2024-183T08:30:45.250")

    def test_output_always_matches_the_day_of_year_shape_merlin_accepts(self):
        import re as _re
        # Day-of-year values carry no zone suffix (the schema's spelling is
        # "2024-183T00:00:00"); ISO values may carry Z or an explicit offset.
        for start in (
            "2024-060T00:00:00.000000",
            "2024-07-01T00:00:00Z",
            "2024-07-01T00:00:00+00:00",
            "2023-001T23:59:59.999",
        ):
            bundle = make_bundle(simulation={
                "simulationStartTime": start,
                "simulationEndTime": "2025-01-01T00:00:00Z",
                "spans": [],
                "resources": [],
            })
            self.assertRegex(
                b2a.derive_dataset_start(bundle),
                r"^\d{4}-\d{3}T\d{2}:\d{2}:\d{2}\.\d{3}$",
                f"datasetStart derived from {start!r} is not day-of-year",
            )

    def test_independent_of_plan_start_time(self):
        # datasetStart must come from simulation.simulationStartTime, not
        # plan.startTime, even when they differ.
        bundle = make_bundle()
        bundle["plan"]["startTime"] = "2020-001T00:00:00.000000"
        self.assertEqual(b2a.derive_dataset_start(bundle), "2024-060T00:00:00.000")


class TestPlanInsertInput(unittest.TestCase):
    def test_fields_match_ui_shape(self):
        bundle = make_bundle()
        plan_input = b2a.build_plan_insert_input(bundle, model_id=42)
        self.assertEqual(
            plan_input,
            {
                "duration": "02:00:00.000000",
                "model_id": 42,
                "name": "test plan",
                "start_time": "2024-060T00:00:00.000000",
            },
        )
        self.assertEqual(set(plan_input.keys()), {"duration", "model_id", "name", "start_time"})


class TestActivityDirectiveInsertInput(unittest.TestCase):
    def test_basic_fields(self):
        directive = {
            "id": 7,
            "type": "TurnOn",
            "name": "turn it on",
            "startOffset": "+00:10:00.000000",
            "arguments": {"power": 5},
            "metadata": {"subsystem": "power"},
        }
        result = b2a.build_activity_directive_insert_input(directive, plan_id=99)
        self.assertEqual(
            result,
            {
                "anchor_id": None,
                "anchored_to_start": True,
                "arguments": {"power": 5},
                "metadata": {"subsystem": "power"},
                "name": "turn it on",
                "plan_id": 99,
                "start_offset": "00:10:00.000000",
                "type": "TurnOn",
            },
        )

    def test_defaults_when_optional_fields_absent(self):
        directive = {"id": 1, "type": "Foo", "startOffset": "+00:00:00.000000"}
        result = b2a.build_activity_directive_insert_input(directive, plan_id=1)
        self.assertEqual(result["name"], "Foo")
        self.assertEqual(result["arguments"], {})
        self.assertEqual(result["metadata"], {})
        self.assertTrue(result["anchored_to_start"])
        self.assertIsNone(result["anchor_id"])

    def test_anchored_to_start_false_is_respected(self):
        directive = {"id": 1, "type": "Foo", "startOffset": "+00:00:00.000000", "anchoredToStart": False}
        result = b2a.build_activity_directive_insert_input(directive, plan_id=1)
        self.assertFalse(result["anchored_to_start"])

    def test_anchor_id_resolved_via_map(self):
        directive = {"id": 2, "type": "Bar", "startOffset": "+00:00:00.000000", "anchorId": 1}
        result = b2a.build_activity_directive_insert_input(directive, plan_id=1, anchor_id_map={1: 501})
        self.assertEqual(result["anchor_id"], 501)

    def test_anchor_id_none_when_no_map_provided(self):
        # First pass: anchors are always inserted null, resolved later.
        directive = {"id": 2, "type": "Bar", "startOffset": "+00:00:00.000000", "anchorId": 1}
        result = b2a.build_activity_directive_insert_input(directive, plan_id=1)
        self.assertIsNone(result["anchor_id"])


class TestAnchorUpdatePass(unittest.TestCase):
    def test_finds_updates_for_resolvable_anchors(self):
        directives = [
            {"id": 1, "type": "Parent", "startOffset": "+00:00:00.000000"},
            {"id": 2, "type": "Child", "startOffset": "+00:05:00.000000", "anchorId": 1},
        ]
        bundle_id_to_real_id = {1: 501, 2: 502}
        updates = b2a.plan_directives_needing_anchor_update(directives, bundle_id_to_real_id)
        self.assertEqual(updates, [(502, 501)])

    def test_directives_without_anchor_are_skipped(self):
        directives = [{"id": 1, "type": "Solo", "startOffset": "+00:00:00.000000"}]
        updates = b2a.plan_directives_needing_anchor_update(directives, {1: 501})
        self.assertEqual(updates, [])

    def test_unresolvable_anchor_target_is_skipped_not_crashed(self):
        # anchorId points at a bundle id that was never inserted (e.g.
        # filtered out upstream). Must not raise or fabricate a reference.
        directives = [{"id": 2, "type": "Child", "startOffset": "+00:00:00.000000", "anchorId": 999}]
        updates = b2a.plan_directives_needing_anchor_update(directives, {2: 502})
        self.assertEqual(updates, [])

    def test_update_mutation_variables_shape(self):
        variables = b2a.build_activity_directive_anchor_update(real_id=502, plan_id=7, real_anchor_id=501)
        self.assertEqual(
            variables,
            {"id": 502, "plan_id": 7, "activityDirectiveSetInput": {"anchor_id": 501}},
        )


class TestDryRunProducesNoHttp(unittest.TestCase):
    """Confirms --dry-run never reaches urllib -- the whole point of it
    being usable without risking a shared database."""

    def test_dry_run_client_never_imports_or_calls_urlopen(self):
        called = {"count": 0}

        import urllib.request as urllib_request

        original_urlopen = urllib_request.urlopen

        def spy(*a, **kw):
            called["count"] += 1
            raise AssertionError("urlopen must never be called in --dry-run mode")

        urllib_request.urlopen = spy
        try:
            bundle = make_bundle(
                simulation={
                    "simulationStartTime": "2024-060T00:00:00.000000",
                    "simulationEndTime": "2024-060T02:00:00.000000",
                    "spans": [],
                    "resources": [
                        {
                            "name": "/r",
                            "type": "discrete",
                            "schema": {"type": "int"},
                            "segments": [{"extent": "01:00:00", "dynamics": 1}],
                        }
                    ],
                }
            )
            client = b2a.DryRunClient()
            plan_id = b2a.import_bundle(bundle, client, plan_id=None, model_id=42)
            self.assertIsInstance(plan_id, int)
            self.assertEqual(called["count"], 0)
            # One CreatePlan, one CreateActivityDirective, one
            # AddExternalDataset (no anchors to update in this bundle).
            self.assertEqual(len(client.calls), 3)
        finally:
            urllib_request.urlopen = original_urlopen

    def test_dry_run_against_existing_plan_id_skips_create_plan(self):
        client = b2a.DryRunClient()
        bundle = make_bundle(activityDirectives=[])
        plan_id = b2a.import_bundle(bundle, client, plan_id=123, model_id=None)
        self.assertEqual(plan_id, 123)
        mutation_names = [q.split("(", 1)[0].split()[-1] for q, _ in client.calls]
        self.assertNotIn("insert_plan_one", mutation_names)


class TestHasuraClientHeaders(unittest.TestCase):
    """The HTTP adapter itself is not exercised against a live server (none
    is available -- see README), but its request-construction is
    deterministic and can be checked without a network call by intercepting
    urllib.request.Request."""

    def test_headers_include_bearer_token_and_role(self):
        import urllib.request as urllib_request

        captured = {}
        original_request_cls = urllib_request.Request

        class CapturingRequest(original_request_cls):
            def __init__(self, url, data=None, headers=None, method=None):
                captured["headers"] = headers
                super().__init__(url, data=data, headers=headers or {}, method=method)

        def fake_urlopen(request, timeout=None):
            raise urllib.error.URLError("no network in tests")

        urllib_request.Request = CapturingRequest
        urllib_request.urlopen = fake_urlopen
        try:
            client = b2a.HasuraClient("http://example.invalid/v1/graphql", auth_token="tok123", role="aerie_admin")
            with self.assertRaises(urllib.error.URLError):
                client.execute("query { x }", {})
        finally:
            urllib_request.Request = original_request_cls

        self.assertEqual(captured["headers"]["Authorization"], "Bearer tok123")
        self.assertEqual(captured["headers"]["x-hasura-role"], "aerie_admin")
        self.assertEqual(captured["headers"]["Content-Type"], "application/json")


class TestSchemaValidation(unittest.TestCase):
    def test_valid_bundle_passes(self):
        bundle = make_bundle()
        # Should not raise.
        b2a.t2b.validate_bundle(bundle, b2a.SCHEMA_PATH, verbose=False)

    def test_missing_required_field_is_rejected(self):
        bundle = make_bundle()
        del bundle["plan"]["startTime"]
        with self.assertRaises(SystemExit):
            b2a.t2b.validate_bundle(bundle, b2a.SCHEMA_PATH, verbose=False)

    def test_bad_resource_type_is_rejected(self):
        bundle = make_bundle()
        bundle["simulation"]["resources"] = [
            {"name": "/r", "type": "not-a-real-type", "schema": {"type": "int"}, "segments": []}
        ]
        with self.assertRaises(SystemExit):
            b2a.t2b.validate_bundle(bundle, b2a.SCHEMA_PATH, verbose=False)

    def test_cli_rejects_invalid_bundle_without_sending_anything(self):
        import json
        import tempfile

        bundle = make_bundle()
        del bundle["bundleVersion"]
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "bad.json")
            with open(path, "w") as f:
                json.dump(bundle, f)
            with self.assertRaises(SystemExit):
                b2a.main([path, "--hasura-url", "http://example.invalid/v1/graphql", "--create-plan", "--model-id", "1", "--dry-run"])


class TestViewDefinitionGeneration(unittest.TestCase):
    """build_view_definition, checked against the same invariants
    generateDefaultView (plandev-ui src/utilities/view.ts) guarantees --
    see the "View generation" section of bundle2aerie.py for the full
    field-for-field cross-check against a live-captured example."""

    def _resources(self):
        return [
            {"name": "/real1", "type": "real"},
            {"name": "/disc1", "type": "discrete"},
            {"name": "/real2", "type": "real"},
        ]

    def test_line_chart_type_for_real_resources(self):
        d = b2a.build_view_definition(self._resources())
        rows_by_name = {row["name"]: row for row in d["plan"]["timelines"][0]["rows"]}
        self.assertEqual(rows_by_name["/real1"]["layers"][0]["chartType"], "line")
        self.assertEqual(rows_by_name["/real2"]["layers"][0]["chartType"], "line")

    def test_x_range_chart_type_for_discrete_resources(self):
        d = b2a.build_view_definition(self._resources())
        rows_by_name = {row["name"]: row for row in d["plan"]["timelines"][0]["rows"]}
        self.assertEqual(rows_by_name["/disc1"]["layers"][0]["chartType"], "x-range")

    def test_one_row_per_resource_plus_activity_row(self):
        resources = self._resources()
        d = b2a.build_view_definition(resources)
        rows = d["plan"]["timelines"][0]["rows"]
        self.assertEqual(len(rows), len(resources) + 1)

    def test_activity_row_present_with_empty_filter(self):
        d = b2a.build_view_definition(self._resources())
        rows = d["plan"]["timelines"][0]["rows"]
        activity_row = rows[0]
        self.assertEqual(activity_row["name"], "Activities by Type")
        activity_layer = activity_row["layers"][0]
        self.assertEqual(activity_layer["chartType"], "activity")
        self.assertEqual(activity_layer["filter"], {"activity": {}})

    def test_resource_row_filter_references_resource_by_name(self):
        d = b2a.build_view_definition(self._resources())
        rows = d["plan"]["timelines"][0]["rows"]
        for row in rows[1:]:
            layer = row["layers"][0]
            self.assertEqual(layer["filter"], {"resource": row["name"]})

    def test_row_ids_are_unique(self):
        d = b2a.build_view_definition(self._resources())
        rows = d["plan"]["timelines"][0]["rows"]
        ids = [row["id"] for row in rows]
        self.assertEqual(len(ids), len(set(ids)))

    def test_layer_ids_are_unique(self):
        d = b2a.build_view_definition(self._resources())
        rows = d["plan"]["timelines"][0]["rows"]
        layer_ids = [layer["id"] for row in rows for layer in row["layers"]]
        self.assertEqual(len(layer_ids), len(set(layer_ids)))

    def test_yaxis_ids_are_unique(self):
        d = b2a.build_view_definition(self._resources())
        rows = d["plan"]["timelines"][0]["rows"]
        yaxis_ids = [axis["id"] for row in rows for axis in row["yAxes"]]
        self.assertEqual(len(yaxis_ids), len(set(yaxis_ids)))

    def test_no_resources_yields_activity_row_only(self):
        d = b2a.build_view_definition([])
        rows = d["plan"]["timelines"][0]["rows"]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["name"], "Activities by Type")

    def test_top_level_shape(self):
        d = b2a.build_view_definition(self._resources())
        self.assertEqual(d["version"], 3)
        self.assertIn("plan", d)
        for key in ("activityDirectivesTable", "activitySpansTable", "grid", "iFrames", "simulationEventsTable", "timelines"):
            self.assertIn(key, d["plan"])

    def test_matches_live_generateDefaultView_capture(self):
        """Ground-truth regression test: a real definition generated by
        plandev-ui's own generateDefaultView for an 18-resource plan
        (captured to scratch during development as p6view.json) must match
        build_view_definition's output field-for-field when fed the same
        resource names/types back in. This is the strongest check available
        short of running the actual TypeScript."""
        ref_path = os.environ.get("P6VIEW_JSON")
        if not ref_path or not os.path.exists(ref_path):
            self.skipTest("P6VIEW_JSON not set to a captured generateDefaultView output; skipping ground-truth comparison")
        import json

        with open(ref_path) as f:
            ref = json.load(f)
        ref_rows = ref["plan"]["timelines"][0]["rows"]
        resources = []
        for row in ref_rows[1:]:
            layer = row["layers"][0]
            resource_type = "real" if layer["chartType"] == "line" else "discrete"
            resources.append({"name": row["name"], "type": resource_type})

        got = b2a.build_view_definition(resources)
        self.assertEqual(got["plan"]["timelines"], ref["plan"]["timelines"])
        self.assertEqual(got["plan"]["activityDirectivesTable"], ref["plan"]["activityDirectivesTable"])
        self.assertEqual(got["plan"]["activitySpansTable"], ref["plan"]["activitySpansTable"])
        self.assertEqual(got["plan"]["grid"], ref["plan"]["grid"])
        self.assertEqual(got["plan"]["iFrames"], ref["plan"]["iFrames"])
        self.assertEqual(got["plan"]["simulationEventsTable"], ref["plan"]["simulationEventsTable"])


class TestViewDefinitionSchemaValidation(unittest.TestCase):
    def test_generated_definition_validates(self):
        d = b2a.build_view_definition(
            [{"name": "/a", "type": "real"}, {"name": "/b", "type": "discrete"}]
        )
        # Should not raise.
        b2a.validate_view_definition(d, b2a.VIEW_SCHEMA_PATH, verbose=False)

    def test_empty_resources_definition_validates(self):
        d = b2a.build_view_definition([])
        b2a.validate_view_definition(d, b2a.VIEW_SCHEMA_PATH, verbose=False)

    def test_broken_definition_is_rejected(self):
        d = b2a.build_view_definition([{"name": "/a", "type": "real"}])
        del d["plan"]["grid"]
        with self.assertRaises(SystemExit):
            b2a.validate_view_definition(d, b2a.VIEW_SCHEMA_PATH, verbose=False)


class TestViewSchemaVendoring(unittest.TestCase):
    """The vendored schema/ui-view-schema-v3.json must stay byte-identical
    to plandev-ui's own copy, or generated views could pass our validation
    while failing the UI's real runtime checks (or vice versa). This test
    is best-effort: it only runs when plandev-ui is checked out beside this
    repo, which is not guaranteed in every environment."""

    def test_vendored_schema_matches_plandev_ui(self):
        candidates = [
            os.path.join(HERE, "..", "..", "..", "plandev-ui", "src", "schemas", "ui-view-schema-v3.json"),
            os.path.expanduser("~/Developer/plandev-ui/src/schemas/ui-view-schema-v3.json"),
        ]
        ui_schema_path = next((c for c in candidates if os.path.exists(c)), None)
        if ui_schema_path is None:
            self.skipTest("plandev-ui not found beside this repo; cannot compare vendored schema for drift")

        with open(b2a.VIEW_SCHEMA_PATH, "rb") as f:
            vendored = f.read()
        with open(ui_schema_path, "rb") as f:
            live = f.read()
        self.assertEqual(
            vendored,
            live,
            "schema/ui-view-schema-v3.json has drifted from plandev-ui's copy -- re-vendor it "
            "(cp plandev-ui/src/schemas/ui-view-schema-v3.json contrib/bundle2aerie/schema/)",
        )


class TestCreateViewDryRun(unittest.TestCase):
    """--dry-run must print the view definition and insert nothing, exactly
    like the bundle-import mutations."""

    def test_create_view_dry_run_inserts_nothing_but_returns_fake_id(self):
        bundle = make_bundle(
            simulation={
                "simulationStartTime": "2024-060T00:00:00.000000",
                "simulationEndTime": "2024-060T02:00:00.000000",
                "spans": [],
                "resources": [
                    {"name": "/r", "type": "discrete", "schema": {"type": "boolean"}, "segments": []},
                ],
            }
        )
        client = b2a.DryRunClient()
        view_id, url = b2a.create_view(bundle, client, plan_id=42, ui_base_url="http://localhost:3000")
        self.assertIsInstance(view_id, int)
        self.assertEqual(url, f"http://localhost:3000/plans/42?viewId={view_id}")
        # DryRunClient never touches the network -- see TestDryRunProducesNoHttp
        # for the analogous assertion on import_bundle's own mutations.
        self.assertEqual(len(client.calls), 1)
        query, variables = client.calls[0]
        self.assertIn("insert_view_one", query)
        self.assertEqual(variables["object"]["definition"]["plan"]["timelines"][0]["rows"][1]["name"], "/r")

    def test_cli_create_view_dry_run_end_to_end(self):
        import json
        import tempfile

        bundle = make_bundle(
            simulation={
                "simulationStartTime": "2024-060T00:00:00.000000",
                "simulationEndTime": "2024-060T02:00:00.000000",
                "spans": [],
                "resources": [
                    {"name": "/r1", "type": "real", "schema": {"type": "real"}, "segments": []},
                    {"name": "/r2", "type": "discrete", "schema": {"type": "boolean"}, "segments": []},
                ],
            }
        )
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "bundle.json")
            with open(path, "w") as f:
                json.dump(bundle, f)
            import urllib.request as urllib_request

            def fail_urlopen(*a, **kw):
                raise AssertionError("dry-run must never touch the network")

            original_urlopen = urllib_request.urlopen
            urllib_request.urlopen = fail_urlopen
            try:
                rc = b2a.main(
                    [
                        path,
                        "--hasura-url",
                        "http://example.invalid/v1/graphql",
                        "--create-plan",
                        "--model-id",
                        "7",
                        "--create-view",
                        "--dry-run",
                        "-v",
                    ]
                )
            finally:
                urllib_request.urlopen = original_urlopen
        self.assertEqual(rc, 0)


class TestEndToEndDryRun(unittest.TestCase):
    """A fuller dry-run exercising the CLI entrypoint against a temp bundle
    file, still with zero network access."""

    def test_main_dry_run_returns_zero_and_writes_nothing(self):
        import json
        import tempfile

        bundle = make_bundle(
            activityDirectives=[
                {"id": 1, "type": "Parent", "startOffset": "+00:00:00.000000"},
                {"id": 2, "type": "Child", "startOffset": "+00:05:00.000000", "anchorId": 1},
            ],
            simulation={
                "simulationStartTime": "2024-060T00:00:00.000000",
                "simulationEndTime": "2024-060T02:00:00.000000",
                "spans": [],
                "resources": [
                    {
                        "name": "/r",
                        "type": "discrete",
                        "schema": {"type": "boolean"},
                        "segments": [
                            {"extent": "01:00:00", "dynamics": True},
                            {"extent": "01:00:00", "dynamics": None},
                        ],
                    }
                ],
            },
        )
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "bundle.json")
            with open(path, "w") as f:
                json.dump(bundle, f)
            rc = b2a.main(
                [path, "--hasura-url", "http://example.invalid/v1/graphql", "--create-plan", "--model-id", "7", "--dry-run", "-v"]
            )
        self.assertEqual(rc, 0)


@unittest.skipUnless(os.environ.get("AERIE_URL"), "set AERIE_URL to run this against a real Hasura instance")
class TestLiveIntegrationSmoke(unittest.TestCase):
    """Never run in CI or during development of this tool (no instance was
    available). Exists so that anyone with a live stack can validate the
    encoding mapping end to end per OFFLINE_BUNDLE_IMPORT_PLAN.md Stage 1's
    acceptance criteria, without this tool ever assuming such a stack
    exists."""

    def test_smoke(self):
        client = b2a.HasuraClient(os.environ["AERIE_URL"], auth_token=os.environ.get("AERIE_AUTH_TOKEN"))
        bundle = make_bundle()
        plan_id = b2a.import_bundle(bundle, client, plan_id=None, model_id=int(os.environ["AERIE_MODEL_ID"]))
        self.assertIsInstance(plan_id, int)


if __name__ == "__main__":
    unittest.main()
