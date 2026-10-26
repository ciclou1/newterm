# Production static frontend: built once, served by a non-root nginx.
ARG NODE_IMAGE=node@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914
ARG NGINX_IMAGE=nginx@sha256:060849a174666c53439dc7cecbf8f67e225c7cbfb1a44ca97915d9a172a81e6d
FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM ${NGINX_IMAGE}
RUN rm -f /etc/nginx/conf.d/default.conf \
    && mkdir -p /var/cache/nginx/client_temp \
    && chown -R nginx:nginx /var/cache/nginx /var/run /etc/nginx/conf.d
COPY deploy/prod/frontend/nginx.conf /etc/nginx/nginx.conf
COPY deploy/prod/frontend/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
