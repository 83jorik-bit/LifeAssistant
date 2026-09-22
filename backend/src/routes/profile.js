const express = require('express');
const db = require('../db');

const router = express.Router();

router.get('/', (req, res) => {
  const user = db.prepare('SELECT name, email, provider, created_at FROM users WHERE id = ?').get(req.userId);
  if (!user) return res.status(404).json({ error: 'Пользователь не найден' });
  res.json(user);
});

module.exports = router;
