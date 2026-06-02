create table merlin.directive_source_is_activity
(
    scheduled_directive_id integer NOT NULL,
    scheduled_plan_id integer NOT NULL,
    referenced_directive_id integer NOT NULL,
    valid boolean NOT NULL default true,

    -- TODO: add scheduling run information as well!!

    constraint directive_source_is_activity_pkey
      primary key (scheduled_plan_id, referenced_directive_id, scheduled_directive_id),

    constraint referenced_directive_exists
      foreign key (referenced_directive_id, scheduled_plan_id)
      references merlin.activity_directive (id, plan_id)
      on update set null
      on delete set null,
    constraint scheduled_directive_exists
      foreign key (scheduled_directive_id, scheduled_plan_id)
      references merlin.activity_directive (id, plan_id)
      on update cascade
      on delete cascade,
    constraint plan_exists
      foreign key (scheduled_plan_id)
      references merlin.plan (id)
      on update cascade
      on delete cascade
);

-- create trigger that, on any change to the activity directive, will invalidate the link
CREATE OR REPLACE FUNCTION merlin.update_directive_source_is_activity_invalid()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE LOG 'Fired!!!!';

    -- Only act if the value actually changed
    IF NEW.start_offset IS DISTINCT FROM OLD.start_offset THEN
        UPDATE merlin.directive_source_is_activity
        SET valid = false
        WHERE referenced_directive_id = NEW.id and scheduled_plan_id = NEW.plan_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_invalidate_b_on_date_change
	AFTER UPDATE ON merlin.activity_directive
FOR EACH ROW
	EXECUTE FUNCTION merlin.update_directive_source_is_activity_invalid();
