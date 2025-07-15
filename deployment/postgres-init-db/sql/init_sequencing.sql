/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Tables
  \ir tables/sequencing/channel_dictionary.sql
  \ir tables/sequencing/command_dictionary.sql
  \ir tables/sequencing/parameter_dictionary.sql
  \ir tables/sequencing/sequence_adaptation.sql
  \ir tables/sequencing/parcel.sql
  \ir tables/sequencing/expansion_set.sql
  \ir tables/sequencing/expansion_rule.sql
  \ir tables/sequencing/expansion_set_to_rule.sql
  \ir tables/sequencing/expansion_run.sql
  \ir tables/sequencing/activity_instance_commands.sql
  \ir tables/sequencing/sequence.sql
  \ir tables/sequencing/sequence_to_simulated_activity.sql
  \ir tables/sequencing/parcel_to_parameter_dictionary.sql
  \ir tables/sequencing/workspace.sql
  \ir tables/sequencing/workspace_collaborators.sql
  \ir tables/sequencing/expanded_sequences.sql
  \ir tables/sequencing/sequence_filter.sql
  \ir tables/sequencing/sequence_template.sql
  \ir tables/sequencing/expanded_templates.sql

  -- Views
  \ir views/sequencing/expansion_set_rule_view.sql
  \ir views/sequencing/rule_expansion_set_view.sql

end;
