FROM node:20-slim

# better-sqlite3 собирается из исходников при установке — нужны инструменты сборки.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY src ./src

ENV DB_DIR=/data
VOLUME ["/data"]

EXPOSE 3000
CMD ["node", "src/server.js"]
