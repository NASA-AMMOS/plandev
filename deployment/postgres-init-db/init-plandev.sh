#!/usr/bin/env bash

set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
  \echo 'Initializing aerie user...'
  CREATE USER "$AERIE_USERNAME" WITH PASSWORD '$AERIE_PASSWORD';
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

  \echo 'Initializing "$PLANDEV_DB" database...'
  CREATE DATABASE "$PLANDEV_DB" OWNER "$AERIE_USERNAME";
  \connect "$PLANDEV_DB"
  ALTER SCHEMA public OWNER TO "$AERIE_USERNAME";
  \connect postgres
  \echo 'Done!'

  \echo 'Initializing "$PLANDEV_METADATA_DB" database...'
  CREATE DATABASE "$PLANDEV_METADATA_DB";
  GRANT ALL PRIVILEGES ON DATABASE "$PLANDEV_METADATA_DB" TO "$AERIE_USERNAME";
  \echo 'Done!'
EOSQL

export PGPASSWORD="$AERIE_PASSWORD"

psql -v ON_ERROR_STOP=1 --username "$AERIE_USERNAME" --dbname "$PLANDEV_DB" <<-EOSQL
  \set aerie_user $AERIE_USERNAME
  \set gateway_user $GATEWAY_DB_USER
  \set merlin_user $MERLIN_DB_USER
  \set scheduler_user $SCHEDULER_DB_USER
  \set sequencing_user $SEQUENCING_DB_USER
  \set dbName $PLANDEV_DB
  \echo 'Initializing database objects...'
  \ir /docker-entrypoint-initdb.d/sql/init.sql
  \echo 'Done!'
EOSQL
