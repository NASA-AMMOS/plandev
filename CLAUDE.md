# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the PlanDev codebase.

## Project Overview

PlanDev is a NASA-developed software framework for modeling spacecraft missions. It provides:
- A Java-based mission modeling library (Merlin)
- A discrete-event simulator
- TypeScript DSLs for scheduling, constraints, command expansion, and sequences
- GraphQL API via Hasura
- PostgreSQL database for persistence

## Build Commands

```bash
# Build all Java projects
./gradlew assemble

# Run all unit tests
./gradlew test

# Build specific subproject
./gradlew :merlin-server:assemble
./gradlew :scheduler-server:assemble

# Check for dependency updates
./gradlew dependencyUpdates
```

## Running PlanDev Locally

```bash
# Copy environment template and fill in values
cp .env.template .env

# Start all services with Docker Compose
docker-compose up --build --detach

# Stop all services
docker-compose down

# Remove volumes (clean database)
docker-compose down && docker volume prune
```

**Default local endpoints:**
- UI: http://localhost
- Hasura Console: http://localhost:8080
- Merlin Server: http://localhost:27183
- Sequencing Server: http://localhost:27184
- Scheduler Server: http://localhost:27185
- Gateway: http://localhost:9000

## Architecture

### Core Java Services (Java 21, Gradle)
- **merlin-server** - Planning and simulation service (port 27183)
- **merlin-worker** - Worker for executing simulations
- **merlin-driver** - Discrete-event simulation engine
- **merlin-framework** - Mission modeling library
- **scheduler-server** - Scheduling service (port 27185)
- **scheduler-worker** - Worker for executing scheduling goals
- **scheduler-driver** - Goal-oriented scheduling engine
- **constraints** - Constraint checking library

### TypeScript/Node Services
- **sequencing-server** - Sequence generation and management (port 27184)
- **action-server** - Action execution service (port 27186)

### Supporting Components
- **hasura** - GraphQL API layer over PostgreSQL
- **postgres** - Database (uses deployment/postgres-init-db for schema)
- **plandev-gateway** - Authentication gateway (separate repo: NASA-AMMOS/plandev-gateway)
- **plandev-ui** - Web client (separate repo: NASA-AMMOS/plandev-ui)

## Key Directories

```
merlin-framework/        # Core mission modeling API
merlin-sdk/             # Interface between models and driver
contrib/                # Convenience classes for mission models
examples/               # Example mission models (banananation, etc.)
procedural/             # Procedural scheduling/constraints libraries
deployment/             # Hasura metadata and Postgres init scripts
e2e-tests/              # End-to-end integration tests
db-tests/               # Database unit tests
```

## Testing

```bash
# Unit tests
./gradlew test

# Run specific test class
./gradlew :merlin-server:test --tests "*.ConstraintsTest"

# E2E tests (requires running PlanDev instance)
./gradlew :e2e-tests:test
```

E2E tests are in `e2e-tests/src/test/java/gov/nasa/jpl/plandev/e2e/` and interact with the full stack via GraphQL.

## Mission Model Development

Mission models are Java projects that use `merlin-framework`. See `examples/banananation/` for a reference implementation.

Key annotations:
- `@MissionModel` - Marks the model entry point
- `@ActivityType` - Defines activity types
- `@Export.Parameter` - Exports configurable parameters

Build a mission model JAR:
```bash
./gradlew :examples:banananation:assemble
```

## TypeScript Components

The sequencing-server and action-server use TypeScript:

```bash
cd sequencing-server
npm install
npm run build

cd action-server
npm install
npm run build
```

## Database

Schema files are in `deployment/postgres-init-db/`. The database uses multiple schemas:
- `merlin` - Plans, activities, simulations
- `scheduler` - Scheduling goals and requests
- `sequencing` - Sequences and command dictionaries
- `hasura` - Hasura metadata
- `permissions` - User roles and permissions

## Git Workflow

- Branch from `develop`
- Use `git rebase` over `git merge`
- PR requires 2 approvals and passing CI
- Use merge commits (not squash)
