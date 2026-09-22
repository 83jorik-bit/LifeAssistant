// Хранилище на SQLite. Для нагрузки, которую реально создаёт одно приложение
// с небольшой аудиторией, отдельная СУБД (PostgreSQL и т.п.) избыточна: SQLite
// прекрасно выдерживает десятки тысяч пользователей на одном файле, а разворачивать
// и обслуживать почти нечего. Если аудитория вырастет на порядки — тогда есть смысл
// переезжать, но не раньше.
const path = require('node:path');
const Database = require('better-sqlite3');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, '..', 'data', 'app.db');

const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    provider      TEXT NOT NULL,            -- 'yandex' | 'vk'
    external_id   TEXT NOT NULL,            -- id пользователя у провайдера
    name          TEXT NOT NULL,
    email         TEXT,
    created_at    INTEGER NOT NULL,
    UNIQUE (provider, external_id)
  );

  -- Согласие на обработку персональных данных. Строки НЕ удаляются при отзыве —
  -- отзыв только проставляет revoked_at. Для доказательства факта согласия важно
  -- хранить историю подписаний, а не последнее состояние поверх предыдущего.
  CREATE TABLE IF NOT EXISTS consents (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    version             INTEGER NOT NULL,
    signature_png_base64 TEXT NOT NULL,
    signed_at           INTEGER NOT NULL,
    revoked_at          INTEGER
  );

  -- Бюджет храним одним JSON-блобом на пользователя: клиент синхронизирует его
  -- целиком (см. PUT /api/budget). Это самая простая модель синхронизации без
  -- конфликтов слияния — оправдана тем, что приложением пока пользуются с одного
  -- устройства за раз. Если появится параллельная работа с двух устройств,
  -- этого будет недостаточно и потребуется версионирование записей.
  CREATE TABLE IF NOT EXISTS budgets (
    user_id     INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    data_json   TEXT NOT NULL,
    updated_at  INTEGER NOT NULL
  );
`);

module.exports = db;
