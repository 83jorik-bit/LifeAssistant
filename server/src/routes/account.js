const express = require('express');
const db = require('../db');
const { requireAuth } = require('../auth');

const router = express.Router();
router.use(requireAuth);

// Полное удаление: пользователь и всё, что на него ссылается (ON DELETE CASCADE
// в схеме — consents и budgets уходят вместе с ним). В отличие от отзыва согласия,
// здесь речь именно о праве на удаление всех данных, а не о доказательном следе.
router.delete('/', (req, res) => {
  db.prepare('DELETE FROM users WHERE id = ?').run(req.userId);
  res.json({ ok: true });
});

module.exports = router;
