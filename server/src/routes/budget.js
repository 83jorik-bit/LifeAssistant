const express = require('express');
const db = require('../db');
const { requireAuth } = require('../auth');

const router = express.Router();
router.use(requireAuth);

router.get('/', (req, res) => {
  const row = db.prepare('SELECT data_json, updated_at FROM budgets WHERE user_id = ?').get(req.userId);
  if (!row) return res.json({ data: null, updatedAt: null });
  res.json({ data: JSON.parse(row.data_json), updatedAt: row.updated_at });
});

// Синхронизация целиком: клиент присылает весь свой бюджет, сервер заменяет им
// предыдущую версию. Простая модель без слияния конфликтов — см. комментарий в db.js.
router.put('/', (req, res) => {
  const data = req.body;
  if (!data || typeof data !== 'object') {
    return res.status(400).json({ error: 'Тело запроса должно быть объектом с данными бюджета' });
  }
  const updatedAt = Date.now();
  db.prepare(
    `INSERT INTO budgets (user_id, data_json, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET data_json = excluded.data_json, updated_at = excluded.updated_at`
  ).run(req.userId, JSON.stringify(data), updatedAt);
  res.json({ ok: true, updatedAt });
});

module.exports = router;
