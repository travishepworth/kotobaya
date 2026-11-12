# Build context: repository root (see docker-compose.yml). Dev image only; release images are T002.
FROM gradle:8.11.1-jdk17 AS core
WORKDIR /src
COPY . .
RUN gradle --no-daemon :core:npmPackage -x test

FROM node:22-slim AS build
RUN npm install -g pnpm
WORKDIR /src
COPY --from=core /src/core/build/npm/core core/build/npm/core
COPY assets assets
COPY clients/typescript clients/typescript
COPY web web
WORKDIR /src/web
RUN pnpm install --frozen-lockfile && pnpm build

FROM nginx:alpine
COPY --from=build /src/web/build /usr/share/nginx/html
RUN printf 'server { listen 3000; root /usr/share/nginx/html; location / { try_files $uri /index.html; } }\n' > /etc/nginx/conf.d/default.conf
EXPOSE 3000
