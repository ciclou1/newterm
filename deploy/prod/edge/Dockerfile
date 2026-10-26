ARG NGINX_IMAGE=nginx@sha256:060849a174666c53439dc7cecbf8f67e225c7cbfb1a44ca97915d9a172a81e6d
FROM ${NGINX_IMAGE}

RUN rm -f /etc/nginx/conf.d/default.conf \
    && mkdir -p /var/cache/nginx/client_temp \
    && chown -R nginx:nginx /var/cache/nginx /var/run /etc/nginx/conf.d

COPY nginx.conf /etc/nginx/nginx.conf
COPY default.conf /etc/nginx/conf.d/default.conf

# nginx UID/GID in the official alpine image.
USER 101:101
EXPOSE 8080 8443

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD wget -qO- --no-check-certificate https://127.0.0.1:8443/healthz || exit 1
