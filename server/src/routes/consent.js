const express = require('express');
const db = require('../db');
const { requireAuth } = require('../auth');

const router = express.Router();
router.use(requireAuth);

// Последнее (не обязательно активное) согласие пользователя.
router.get('/', (req, res) => {
  const row = db
    .prepare('SELECT version, signed_at, revoked_at FROM consents WHERE user_id = ? ORDER BY id DESC LIMIT 1')
    .get(req.userId);
  // Подпись (signature_png_base64) в ответе намеренно не возвращаем — она нужна только
  // как доказательство при споре, а не для повседневной работы приложения. Незачем
  // гонять картинку туда-обратно при каждой проверке статуса согласия.
  res.json(row || null);
});

// Подписание согласия. Создаёт НОВУЮ запись, а не перезаписывает предыдущую —
// история подписаний нужна как доказательство при возможном споре.
router.post('/', (req, res) => {
  const { version, signedAtEpochSeconds, signatureBase64 } = req.body || {};
  if (!version || !signedAtEpochSeconds || !signatureBase64) {
    return res.status(400).json({ error: 'Нужны version, signedAtEpochSeconds, signatureBase64' });
  }
  db.prepare(
    'INSERT INTO consents (user_id, version, signature_png_base64, signed_at) VALUES (?, ?, ?, ?)'
  ).run(req.userId, version, signatureBase64, signedAtEpochSeconds);
  res.status(201).json({ ok: true });
});

// Отзыв. Помечает последнее согласие отозванным, а не удаляет строку — см. схему в db.js.
router.delete('/', (req, res) => {
  db.prepare(
    `UPDATE consents SET revoked_at = ?
     WHERE user_id = ? AND id = (SELECT id FROM consents WHERE user_id = ? ORDER BY id DESC LIMIT 1)`
  ).run(Date.now(), req.userId, req.userId);
  res.json({ ok: true });
});

module.exports = router;
