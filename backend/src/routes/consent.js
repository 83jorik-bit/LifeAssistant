const express = require('express');
const db = require('../db');

const router = express.Router();

// Согласие — это факт, на который может понадобиться сослаться позже,
// поэтому сохраняем как есть, без валидации содержимого подписи: сервер не
// решает, что считать подписью, это ответственность приложения-клиента.
router.put('/', (req, res) => {
  const { version, signedAtEpochSeconds, signatureBase64 } = req.body || {};
  if (!version || !signedAtEpochSeconds || !signatureBase64) {
    return res.status(400).json({ error: 'Нужны version, signedAtEpochSeconds и signatureBase64' });
  }

  const signedAt = new Date(signedAtEpochSeconds * 1000).toISOString();
  db.prepare(
    `INSERT INTO consents (user_id, version, signed_at, signature_png_base64)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET version = excluded.version,
       signed_at = excluded.signed_at, signature_png_base64 = excluded.signature_png_base64`
  ).run(req.userId, version, signedAt, signatureBase64);

  res.json({ ok: true });
});

router.get('/', (req, res) => {
  const record = db
    .prepare('SELECT version, signed_at FROM consents WHERE user_id = ?')
    .get(req.userId);
  res.json(record || null);
});

module.exports = router;
