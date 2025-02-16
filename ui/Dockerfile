ARG APP_HOME=/home/node/app

# build environment
FROM node:18 AS builder
ARG APP_HOME
WORKDIR ${APP_HOME}
COPY . ${APP_HOME}
RUN yarn install
RUN yarn build

# production environment
FROM nginx:1.27.4
ARG APP_HOME
COPY --from=builder ${APP_HOME}/build /var/www
COPY ./etc/prod/nginx.conf /etc/nginx/nginx.conf
WORKDIR /usr/share/nginx/html
CMD ["nginx", "-g", "daemon off;"]
