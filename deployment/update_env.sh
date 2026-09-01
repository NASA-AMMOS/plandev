#!/bin/bash

# Fix Gateway Container Permissions
docker compose exec -u root plandev_gateway chown -R node:node /app/files

# Add new envvars to the .env
GATEWAY_CONTAINER_ID=$(docker ps | grep gateway | awk {'print $1'})
VOLUME_NAME=$(docker inspect -f '{{ .Mounts }}' $GATEWAY_CONTAINER_ID | grep aerie_file_store | awk {'print $2'})
echo PLANDEV_FILE_STORE_NAME="$VOLUME_NAME" | tee -a .env
echo PLANDEV_DATABASE_NAME=aerie | tee -a .env
echo PLANDEV_METADATA_DATABASE_NAME=aerie_hasura | tee -a .env
