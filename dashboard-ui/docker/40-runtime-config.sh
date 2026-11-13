#!/bin/sh
# Runs from the nginx image's /docker-entrypoint.d before nginx starts.
#  1. writes config.js (the SPA's only runtime setting: where the gateway is)
#  2. renders the security-header snippet, whose CSP connect-src must name the gateway and Keycloak
set -eu

case "$GATEWAY_URL" in http://*|https://*) ;; *) echo "GATEWAY_URL must be an http(s) URL" >&2; exit 1 ;; esac
case "$KEYCLOAK_URL" in http://*|https://*) ;; *) echo "KEYCLOAK_URL must be an http(s) URL" >&2; exit 1 ;; esac
GATEWAY_URL="${GATEWAY_URL%/}"
KEYCLOAK_URL="${KEYCLOAK_URL%/}"

printf "window.__CLINICALHARMONY__ = { gatewayUrl: '%s' };\n" "$GATEWAY_URL" > /usr/share/nginx/html/config.js

mkdir -p /etc/nginx/snippets
sed -e "s|\${GATEWAY_URL}|$GATEWAY_URL|g" -e "s|\${KEYCLOAK_URL}|$KEYCLOAK_URL|g" \
    /etc/nginx/snippets/security-headers.conf.template > /etc/nginx/snippets/security-headers.conf

echo "40-runtime-config: gateway=$GATEWAY_URL keycloak=$KEYCLOAK_URL"
