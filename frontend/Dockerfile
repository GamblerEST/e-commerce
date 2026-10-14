# Build-only image — no runtime stage. The SPA it produces is static files
# served by Caddy (see docker-compose.yml's `caddy` service mounting
# ./frontend/dist), not something this container runs on its own. Exists so
# `docker compose up` alone produces a working frontend with no host
# npm/node install required — see the frontend-build service's comment in
# docker-compose.yml for why the build runs as CMD, not a Dockerfile RUN.
FROM node:20-alpine
WORKDIR /app

# Dependencies in their own layer, same reasoning as backend/Dockerfile —
# reused as long as package*.json is unchanged.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
CMD ["npm", "run", "build"]
