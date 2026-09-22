const express = require('express');
const db = require('../db');
const { requireAuth } = require('../auth');

const router = express.Router();
router.use(requireAuth);

router.get('/', (req, res) => {
  const row = db.prepare('SELECT name, email FROM users WHERE id = ?').get(req.userId);
  if (!row) return res.status(404).json({ error: 'Пользователь не найден' });
  res.json(row);
});

// Ручное изменение имени/почты — на случай, если пользователь правит профиль
// в приложении отдельно от того, что прислал провайдер входа.
router.put('/', (req, res) => {
  const { name, email } = req.body || {};
  if (!name || typeof name !== 'string') {
    return res.status(400).json({ error: 'Нужно имя (name)' });
  }
  db.prepare('UPDATE users SET name = ?, email = ? WHERE id = ?').run(
    name,
    email || null,
    req.userId
  );
  res.json({ name, email: email || null });
});

module.exports = router;
