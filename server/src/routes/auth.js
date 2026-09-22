const express = require('express');
const db = require('../db');
const { issueSessionToken, fetchProviderProfile } = require('../auth');

const router = express.Router();

// POST /api/auth/login
// body: { provider: "yandex" | "vk", accessToken: string }
router.post('/login', async (req, res) => {
  const { provider, accessToken } = req.body || {};
  if (!provider || !accessToken) {
    return res.status(400).json({ error: 'Нужны provider и accessToken' });
  }

  let providerProfile;
  try {
    providerProfile = await fetchProviderProfile(provider, accessToken);
  } catch (e) {
    // Токен не наш — мы его не выдавали, поэтому 401, а не 500: это не сбой сервера,
    // а недействительные данные на входе.
    return res.status(401).json({ error: e.message });
  }

  const existing = db
    .prepare('SELECT id, name, email FROM users WHERE provider = ? AND external_id = ?')
    .get(provider, providerProfile.externalId);

  let userId;
  if (existing) {
    userId = existing.id;
    // Имя и почта могли обновиться у провайдера — синхронизируем при каждом входе.
    db.prepare('UPDATE users SET name = ?, email = ? WHERE id = ?').run(
      providerProfile.name,
      providerProfile.email,
      userId
    );
  } else {
    const result = db
      .prepare(
        'INSERT INTO users (provider, external_id, name, email, created_at) VALUES (?, ?, ?, ?, ?)'
      )
      .run(provider, providerProfile.externalId, providerProfile.name, providerProfile.email, Date.now());
    userId = result.lastInsertRowid;
  }

  const token = issueSessionToken(userId);
  res.json({
    token,
    profile: { name: providerProfile.name, email: providerProfile.email }
  });
});

module.exports = router;
