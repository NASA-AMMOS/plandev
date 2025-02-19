create table sequencing.template_expansion_run
(
    id integer generated always as identity,
    simulation_dataset_id integer not null,
    model_id integer not null,
    created_at timestamp with time zone not null default now(),
    constraint template_expansion_run_pkey primary key (id)
)
