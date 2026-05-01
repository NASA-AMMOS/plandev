-- Data migration: deduplicate parcel names:
do $$
begin
  -- While there are duplicate names in the parcel table...
  while exists(
    select from sequencing.parcel
    group by name
    having count(name) > 1
  ) loop
    -- ...deduplicate them
    update sequencing.parcel
    set name = name || '(' || ir.row || ')'
    from (
        select id, row_number() over (partition by name) - 1 as row
        from sequencing.parcel
        where name in (
          select p.name
          from sequencing.parcel p
          group by p.name
          having count(1) > 1)
    ) as ir
    where ir.id = parcel.id
    and row > 0;
end loop;
end $$;

-- Add new uniqueness constraint to the parcel name
alter table sequencing.parcel
add unique(name);

call migrations.mark_migration_applied(34);
