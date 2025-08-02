/*
 This file grants permissions to each of the DB users to the schemas.
 It is executed by the AERIE user after the DB schema has been created.

 If granting a certain privilege to 'all X in schema' to a user, include an 'alter default privilege' statement
 'grant all' affects current DB objects, 'alter default' affects future DB objects
*/
begin;
  -- All services may execute functions in the `util_functions` schema
  -- 'routines' includes both functions and procedures
  grant usage on schema util_functions to public;
  grant execute on all routines in schema util_functions to public;
  alter default privileges in schema util_functions grant execute on routines to public;

  -- All services must be able to view the user role permissions table
  grant usage on schema permissions to public;
  grant select on table permissions.user_role_permission to public;

  -- All services can create temp tables
  grant temp on database aerie to public;

  -- All services can read merlin data
  grant usage on schema merlin to public;
  grant select on all tables in schema merlin to public;
  alter default privileges in schema merlin grant select on tables to public;

  -- Revoke create from public in the public schema
  revoke create on schema public from public;

  ------------------------------
  -- Gateway User Permissions --
  ------------------------------
  -- The Gateway is in charge of managing user permissions
  grant select on all tables in schema permissions to :"gateway_user";
  grant insert, update, delete on permissions.users, permissions.users_allowed_roles to :"gateway_user";
  grant execute on all routines in schema permissions to :"gateway_user";

  alter default privileges in schema permissions grant select on tables to :"gateway_user";
  alter default privileges in schema permissions grant execute on routines to :"gateway_user";
  -- The Gateway is in charge of managing uploaded files
  grant select, insert, update, delete on merlin.uploaded_file to :"gateway_user";

  -----------------------------
  -- Merlin User Permissions --
  -----------------------------
  -- Merlin has control of all tables in the merlin schema
  grant select, insert, update, delete on all tables in schema merlin to :"merlin_user";
  grant execute on all routines in schema merlin to :"merlin_user";

  alter default privileges in schema merlin grant select, insert, update, delete on tables to :"merlin_user";
  alter default privileges in schema merlin grant execute on routines to :"merlin_user";

  --------------------------------
  -- Scheduler User Permissions --
  --------------------------------
  -- The Scheduler has control of all tables in the scheduler schema
  grant usage on schema scheduler to :"scheduler_user";
  grant select, insert, update, delete on all tables in schema scheduler to :"scheduler_user";
  grant execute on all routines in schema scheduler to :"scheduler_user";

  alter default privileges in schema scheduler grant select, insert, update, delete on tables to :"scheduler_user";
  alter default privileges in schema scheduler grant execute on routines to :"scheduler_user";

  -- The Scheduler needs to be able to Add/Update Activity Directives in a Plan
  grant insert, update on table merlin.activity_directive to :"scheduler_user";
  grant insert on table merlin.plan to :"scheduler_user";

  -- The Scheduler can write simulation data
  grant insert, update on table merlin.span, merlin.simulation_dataset to :"scheduler_user";
  grant insert on table merlin.profile, merlin.profile_segment, merlin.topic, merlin.event to :"scheduler_user";

  ---------------------------------
  -- Sequencing User Permissions --
  ---------------------------------
  -- The Sequencing Server has control of all tables in the sequencing schema
  grant usage on schema sequencing to :"sequencing_user";
  grant select, insert, update, delete on all tables in schema sequencing to :"sequencing_user";
  grant execute on all routines in schema sequencing to :"sequencing_user";

  alter default privileges in schema sequencing grant select, insert, update, delete on tables to :"sequencing_user";
  alter default privileges in schema sequencing grant execute on routines to :"sequencing_user";

  -----------------------
  -- UI DB Permissions --
  -----------------------
  -- The Aerie User currently has control of all tables in the UI schema
  grant create, usage on schema ui to :"aerie_user";
  grant select, insert, update, delete on all tables in schema ui to :"aerie_user";
  grant execute on all routines in schema ui to :"aerie_user";

  alter default privileges in schema ui grant select, insert, update, delete on tables to :"aerie_user";
  alter default privileges in schema ui grant execute on routines to :"aerie_user";

  ---------------------------
  -- Action DB Permissions --
  ---------------------------
  -- The Action User currently has control of all tables in the actions schema
  grant create, usage on schema actions to :"sequencing_user";
  grant select, insert, update, delete on all tables in schema actions to :"sequencing_user";
  grant execute on all routines in schema actions to :"sequencing_user";

  alter default privileges in schema actions grant select, insert, update, delete on tables to :"sequencing_user";
  alter default privileges in schema actions grant execute on routines to :"sequencing_user";

  -- The Action Server needs to be able to write sequences, right now we're reusing the sequencing user so these are commented out.
  -- grant create, usage on schema sequencing to :"sequencing_user";
  -- grant select, insert, update, delete on table sequencing.user_sequence to :"sequencing_user";
end;
