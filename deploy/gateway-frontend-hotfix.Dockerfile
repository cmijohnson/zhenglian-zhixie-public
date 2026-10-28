ARG BASE_GATEWAY_IMAGE=nginx:1.29-alpine
FROM ${BASE_GATEWAY_IMAGE}

COPY dist /usr/share/nginx/html
