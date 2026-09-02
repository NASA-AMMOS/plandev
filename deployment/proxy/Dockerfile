from docker.io/nginx:alpine3.18

# inject PLANDEV_HOST into nginx.conf
copy ./nginx.conf.template /etc/nginx/templates/default.conf.template

copy ./cert.pem /cert.pem
copy ./key.pem /key.pem
