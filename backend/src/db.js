const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

// Файл базы лежит в /data — это отдельный том в Docker, чтобы данные не
// пропадали при пересборке контейнера. Локально (без Docker) создаётся
// рядом с проектом.
const DB_DIR = process.env.DB_DIR || path.join(__dirname, '..', 'data');
if (!fs.existsSync(DB_DIR)) fs.mkdirSync(DB_DIR, { recursive: true });

const db = new Database(path.join(DB_DIR, 'lifenavigator.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

// Схема сознательно простая: одна строка на пользователя плюс несколько таблиц
// один-ко-многим для бюджета. Это ровно те данные, что раньше жили только
// в SharedPreferences на устройстве (см. ConsentStore/BudgetStorage в приложении) —
// сервер не придумывает новых категорий данных, а зеркалит уже существующие.
db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL,              -- 'yandex' | 'vk'
    provider_user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    email TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(provider, provider_user_id)
  );

  CREATE TABLE IF NOT EXISTS consents (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    signed_at TEXT NOT NULL,
    signature_png_base64 TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS budget_income (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    month TEXT NOT NULL,                 -- '2026-09'
    amount_rub INTEGER NOT NULL,
    PRIMARY KEY (user_id, month)
  );

  CREATE TABLE IF NOT EXISTS budget_limits (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_key TEXT NOT NULL,
    limit_rub INTEGER NOT NULL,
    PRIMARY KEY (user_id, category_key)
  );

  CREATE TABLE IF NOT EXISTS budget_custom_categories (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    title TEXT NOT NULL,
    PRIMARY KEY (user_id, key)
  );

  CREATE TABLE IF NOT EXISTS budget_expenses (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    id TEXT NOT NULL,
    category_key TEXT NOT NULL,
    amount_rub INTEGER NOT NULL,
    note TEXT,
    date_epoch_day INTEGER NOT NULL,
    PRIMARY KEY (user_id, id)
  );
`);

module.exports = db;
