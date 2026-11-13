# dashboard-ui — Phase 8. Build with Node, serve the static bundle with nginx.
# Both are alpine images: unlike eclipse-temurin, node and nginx alpine tags have arm64 manifests.

FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY . .
# Unit/component tests gate the image: a red test suite means no image.
RUN npm test && npm run build

FROM nginx:1.30.6-alpine
# The browser calls the gateway and Keycloak directly (CORS as the gateway has it); nginx only serves
# files. Both URLs are runtime settings: config.js and the CSP are rendered at container start.
ENV GATEWAY_URL=http://localhost:8080 \
    KEYCLOAK_URL=http://localhost:8180
COPY docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker/security-headers.conf /etc/nginx/snippets/security-headers.conf.template
COPY docker/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/40-runtime-config.sh && rm -f /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1:3000/healthz >/dev/null || exit 1
