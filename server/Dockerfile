FROM node:22-bookworm-slim

ENV NODE_ENV=production
ENV PORT=3000
ENV DB_PATH=/var/lib/lifeassistant/app.db

WORKDIR /app

# better-sqlite3 may need native compilation on architectures without a matching prebuilt binary.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

COPY package.json ./
RUN npm install --omit=dev \
    && npm cache clean --force

COPY src ./src

RUN mkdir -p /var/lib/lifeassistant \
    && chown -R node:node /app /var/lib/lifeassistant

USER node

EXPOSE 3000
CMD ["node", "src/index.js"]
