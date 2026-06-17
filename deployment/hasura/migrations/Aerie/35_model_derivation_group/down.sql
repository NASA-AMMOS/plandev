drop trigger populate_derivation_groups_new_plan_trigger on merlin.plan;
drop function merlin.populate_derivation_groups_new_plan cascade;
drop table merlin.model_derivation_group cascade;

call migrations.mark_migration_rolled_back('35');
