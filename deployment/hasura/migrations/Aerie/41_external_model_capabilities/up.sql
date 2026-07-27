-- Record what an external backend says PlanDev may DO with a model, as opposed to what the model is.
--
-- The four tables an external model populates (activity_type, resource_type, mission_model_parameters,
-- plus the model row) describe its type surface. They say nothing about which PlanDev features apply to
-- it, and the features genuinely differ per model in ways PlanDev cannot infer:
--
--   * SCHEDULING. A foreign framework is one of two archetypes. A pure simulator maps directives to
--     profiles and spans and places nothing itself, so PlanDev's scheduler can drive it as an oracle. A
--     forward-dispatch framework -- Blackbird is one -- places activities DURING the simulation, so its
--     schedule is an output. Running PlanDev's scheduler against that pits two schedulers against each
--     other: a goal places A, the simulation runs, and the model's own dispatcher places B, C and D on
--     top. Nothing in a model's declared types distinguishes the two cases.
--   * PLAN IMPORT. Some backends can read their framework's native plan format and hand back directives;
--     most cannot, and the formats are per-framework.
--
-- Storing this as ONE jsonb rather than a boolean column per feature is deliberate, and the reason is a
-- constraint on the UI rather than a taste for flexibility: plandev-ui must never contain a branch that
-- names a particular model or framework. A capability whose payload carries the backend's OWN reason and
-- its OWN format labels lets the UI render "scheduling is unavailable for this model because <text>"
-- without knowing what Blackbird is. A boolean would push that sentence back into the client.
--
-- Shape: an object keyed by capability name, each value an object with at least `supported`.
--
--   {"plandevScheduling": {"supported": false, "reason": "This model schedules its own..."},
--    "planImport":        {"supported": true,
--                          "formats": [{"key":"blackbird-plan-json","label":"Blackbird plan",
--                                       "extensions":[".plan.json"]}]}}
--
-- Never a bare boolean, even where one would do: a capability that is unsupported is exactly the case
-- that needs somewhere to put the explanation, and retrofitting `reason` onto a boolean means changing
-- the wire format later.
--
-- ABSENT MEANS UNSUPPORTED. A backend that declares nothing, or a model registered before this existed,
-- gets today's behavior -- which for scheduling is a refusal. Defaulting the other way would have an old
-- adapter silently opt into a feature nobody verified it could support.
--
-- Like external_identity_hash, this rides the existing revision machinery: any update to mission_model
-- bumps `revision`, and merlin writes only when the value actually differs, so a re-introspection that
-- finds no change is a no-op and does not churn the simulation cache.
alter table merlin.mission_model
  add column external_capabilities jsonb;

comment on column merlin.mission_model.external_capabilities is e''
  'For model_type="external": what the backend reports PlanDev may do with this model, as an object '
  'keyed by capability name -- e.g. {"plandevScheduling":{"supported":false,"reason":"..."}}. Each value '
  'carries at least `supported`, plus whatever that capability needs (an unsupported one carries the '
  'backend''s own `reason`, so the UI can explain it without knowing which framework this is). An absent '
  'capability means UNSUPPORTED. Null for JAR models, whose capabilities are not in question, and for '
  'external models registered before this was recorded.';

call migrations.mark_migration_applied(41);
