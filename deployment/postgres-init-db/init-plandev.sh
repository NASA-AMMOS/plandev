#!/usr/bin/env bash

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
  \echo 'Initializing plandev user...'
  CREATE USER "$PLANDEV_USERNAME" WITH PASSWORD '$PLANDEV_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing gateway user...'
  CREATE USER "$GATEWAY_DB_USER" WITH PASSWORD '$GATEWAY_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing merlin user...'
  CREATE USER "$MERLIN_DB_USER" WITH PASSWORD '$MERLIN_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing scheduler user...'
  CREATE USER "$SCHEDULER_DB_USER" WITH PASSWORD '$SCHEDULER_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing sequencing user...'
  CREATE USER "$SEQUENCING_DB_USER" WITH PASSWORD '$SEQUENCING_DB_PASSWORD';
  \echo 'Done!'

  \echo 'Initializing plandev database...'
  CREATE DATABASE plandev OWNER "$PLANDEV_USERNAME";
  \connect plandev
  ALTER SCHEMA public OWNER TO "$PLANDEV_USERNAME";
  \connect postgres
  \echo 'Done!'

  \echo 'Initializing plandev_hasura database...'
  CREATE DATABASE plandev_hasura;
  GRANT ALL PRIVILEGES ON DATABASE plandev_hasura TO "$PLANDEV_USERNAME";
  \echo 'Done!'
EOSQL

export PGPASSWORD="$PLANDEV_PASSWORD"

psql -v ON_ERROR_STOP=1 --username "$PLANDEV_USERNAME" --dbname "plandev" <<-EOSQL
  \set plandev_user $PLANDEV_USERNAME
  \set gateway_user $GATEWAY_DB_USER
  \set merlin_user $MERLIN_DB_USER
  \set scheduler_user $SCHEDULER_DB_USER
  \set sequencing_user $SEQUENCING_DB_USER
  \echo 'Initializing plandev database objects...'
  \ir /docker-entrypoint-initdb.d/sql/init.sql
  \echo 'Done!'
EOSQL
