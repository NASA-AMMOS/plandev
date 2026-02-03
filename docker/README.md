# Docker

This directory contains additional Dockerfiles for images built by PlanDev.

- [Dockerfile.hasura](./Dockerfile.hasura) - A Hasura Docker image with bundled PlanDev-specific Hasura metadata
- [Dockerfile.postgres](./Dockerfile.postgres) - A Postgres Docker image with bundled PlanDev-specific SQL

## Build

First build PlanDev to make sure the SQL files are properly added to the [deployment](../deployment/) directory:

```sh
cd aerie
./gradlew assemble
```

Next, still from the top-level PlanDev directory, build the images from the provided Dockerfiles:

```sh
docker build -t aerie-hasura -f ./docker/Dockerfile.hasura .
docker build -t aerie-postgres -f ./docker/Dockerfile.postgres .
```

## Run

To run the images you can use the following commands. Note these are just for testing purposes:

```sh
docker run --name aerie-hasura -d -p 8080:8080 aerie-hasura
docker run --name aerie-postgres -d -p 5432:5432 --env-file ./.env aerie-postgres
```
