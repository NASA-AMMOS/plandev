# Network File System (NFS)

This document describes how to deploy and use a [Network File System](https://en.wikipedia.org/wiki/Network_File_System) with PlanDev.

## Server

A container NFS can be added to your [docker-compose.yml](./docker-compose.yml) services:

```yaml
services:
  nfs:
    cap_add:
      - SYS_ADMIN
    container_name: nfs
    environment:
      NFS_EXPORT_0: /data *(rw,no_root_squash,no_subtree_check,fsid=0)
      NFS_PORT: 2049
      NFS_VERSION: 4.2
    hostname: nfs
    image: erichough/nfs-server
    ports:
      - 2049:2049
    restart: always
    volumes:
      - mission_file_store:/data
```

For more information regarding the `NFS_EXPORT_0` export please see the official [erichough/nfs-server documentation](https://github.com/ehough/docker-nfs-server#usage), and the Linux `exports` [man page](https://linux.die.net/man/5/exports).

## Clients

Clients may mount the shared directory by making use of a Docker volume:

```yaml
volumes:
  mission_file_store:
    driver: local
    driver_opts:
      type: nfs4
      o: addr=172.27.0.2,rw,noatime
      device: ":/"
```

In this case, the IP (`172.27.0.2`) was obtained by running:

```sh
$ docker compose up --build --detach
Creating network "plandev_net" with the default driver
Creating nfs ... done
$ docker container ls | grep nfs | cut -d " " -f1
2724af1ec4d2
$ docker inspect 2724af1ec4d2 | grep IPAddress
"IPAddress": "172.27.0.2"
```

The volume may now be attached to an existing service's `volumes` list with:

```yaml
volumes:
  - mission_file_store:/usr/src/app/data
```

`/usr/src/app/data` will now be the mount location of the shared directory.

## Sharing

Continuing the previous section, it's possible to demonstrate file sharing between the NFS host and clients. With the NFS container still running, log into the NFS server container and create a test file:

```
$ docker exec -it 2724af1ec4d2 /bin/bash
root@2724af1ec4d2:/# touch data/from_server.txt
```

Start the default PlanDev services with `docker compose up` and get the container ID with:

```sh
$ docker container ls | grep aerie_gateway_1 | cut -d " " -f1
ba3ef517965e
```

To access existing files and create a new file on the shared NFS run:

```sh
$ docker exec -it ba3ef517965e /bin/bash
root@ba3ef517965e:/usr/src/app# cd data
root@ba3ef517965e:/usr/src/app/data# touch from_client.txt
root@ba3ef517965e:/usr/src/app/data# ls
from_client.txt
from_server.txt
```
