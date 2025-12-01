# Developer

This document describes how to set up your development environment to build and develop PlanDev.

- [Prerequisite Software](#prerequisite-software)
- [Code Editor](#code-editor)
- [Getting the Sources](#getting-the-sources)
- [Building](#building)
- [Testing](#testing)
- [Dependency Updates](#dependency-updates)
- [Environment](#environment)
- [Start PlanDev](#start-plandev)
- [Stop PlanDev](#stop-plandev)
- [Remove Docker Images](#remove-docker-images)
- [Remove Docker Volumes](#remove-docker-volumes)
- [Entering a Docker Container](#entering-a-docker-container)
- [Apple Silicon](#apple-silicon)

## Prerequisite Software

Before you can run PlanDev you must install and configure the following products on your development machine:

- [Git](http://git-scm.com) and/or the [GitHub app](https://desktop.github.com/); [GitHub's Guide to Installing Git](https://help.github.com/articles/set-up-git) is a good source of information.

- [Docker](https://www.docker.com/) which is used to run the PlanDev services.

- [OpenJDK Temurin LTS](https://adoptium.net/temurin/) which is used to build the Java-based PlanDev services. If you're on OSX you can use [brew](https://brew.sh/):

  ```sh
  brew install --cask temurin
  ```

  Make sure you update your `JAVA_HOME` environment variable. For example with [Zsh](https://www.zsh.org/) you can set your `.zshrc` to:

  ```sh
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
  ```

- [PostgreSQL](https://www.postgresql.org) which is used for testing the database. You do not need this normally since PlanDev runs Postgres in a Docker container for development, and you only need it for the [psql](https://www.postgresql.org/docs/current/app-psql.html) command-line tool. **Do not run the Postgres service locally** or it will clash with the PlanDev Postgres Docker container. If you're on OSX you can use brew:

  ```sh
  brew install postgresql
  ```

## Code Editor

If you use [IntelliJ IDEA](https://www.jetbrains.com/idea/), you can import the PlanDev repository into IntelliJ as a Gradle project. No additional configuration is required.

## Getting the Sources

[Clone](https://help.github.com/en/github/creating-cloning-and-archiving-repositories/cloning-a-repository) the PlanDev repository:

```shell
git clone https://github.com/NASA-AMMOS/plandev.git
cd plandev
```

## Building

```sh
cd plandev
./gradlew assemble
```

## Testing

```sh
cd plandev
./gradlew test
```

## Dependency Updates

Use the following task to print a report of the dependencies that have updates available.

```sh
cd plandev
./gradlew dependencyUpdates
```

## Environment

To run the PlanDev services you need to first set the proper environment variables. First copy the template:

```sh
cd plandev
cp .env.template .env
```

Fill out the `.env` file with the following default environment variables (note you should **not** use these values in production):

```sh
PLANDEV_PASSWORD=plandev
PLANDEV_USERNAME=plandev
HASURA_GRAPHQL_ADMIN_SECRET=plandev
HASURA_GRAPHQL_JWT_SECRET='{ "type": "HS256", "key": "oursupersecretsupersecurekey1234567890" }'
POSTGRES_PASSWORD=postgres
POSTGRES_USER=postgres
```

## Start PlanDev

The [docker-compose.yml](../docker-compose.yml) in the root directory deploys PlanDev locally, creating containers using the artifacts from the build step above.

```sh
cd plandev
docker-compose up --build --detach
```

Once PlanDev is started you can visit [http://localhost](http://localhost) to view the [PlanDev UI](https://github.com/NASA-AMMOS/plandev-ui). You can visit [http://localhost:8080](http://localhost:8080) to view the [Hasura Console](https://hasura.io/).

## Stop PlanDev

```sh
cd plandev
docker compose down
```

## Remove Docker Images

Removing a docker image from your local cache forces Docker to either rebuild it or fetch it from a repository (e.g. DockerHub or GitHub Packages).

```sh
docker rmi [image name or image id]
```

## Remove Docker Volumes

Sometimes it's necessary to clear the contents of file system volumes mounted by Docker. For PlanDev this could be needing
to start with a clean install and wanting to delete the database contents, mission model jars, and mission simulation
data files.

First ensure [all containers are down](#stop-plandev). Only once containers are down you can run volume
pruning operation:

```sh
docker volume prune
```

## Entering a Docker Container

At times it is helpful to enter a docker container and inspect the filesystem or run CLI utilities such as
[psql](https://www.postgresql.org/docs/current/app-psql.html) or [hasura-cli](https://hasura.io/docs/latest/hasura-cli/commands/index/). For example a shell can be initialized in the Postgres container with:

```sh
docker exec -it plandev-postgres /bin/sh
```

## Apple Silicon

If you're having issues building the Docker containers with Apple Silicon you can try setting the follow environment variables.
This will disable BuildKit and try to default to using `linux/arm` as the platform.
```sh
export DOCKER_DEFAULT_PLATFORM=linux/arm64
export DOCKER_BUILDKIT=0
```
