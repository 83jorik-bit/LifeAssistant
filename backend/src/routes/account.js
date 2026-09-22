const express = require('express');
const db = require('../db');

const router = express.Router();

// ON DELETE CASCADE в схеме (db.js) сам подчистит consents/budget_* — удалить
// нужно только саму строку пользователя.
router.delete('/', (req, res) => {
  db.prepare('DELETE FROM users WHERE id = ?').run(req.userId);
  res.json({ ok: true });
});

module.exports = router;
