const express = require('express');
const rateLimit = require('express-rate-limit');
const { fetchProviderProfile, upsertUser, issueSessionToken, HttpError } = require('../auth');

const router = express.Router();

// Вход дороже обычного запроса (поход к внешнему провайдеру), и это единственная
// точка, где неаутентифицированный клиент вообще может что-то заставить сервер
// делать — поэтому лимит строже, чем у остальных маршрутов.
const authLimiter = rateLimit({ windowMs: 60_000, limit: 10 });
router.use(authLimiter);

router.post('/:provider', async (req, res) => {
  const provider = req.params.provider;
  const accessToken = req.body?.accessToken;

  if (provider !== 'yandex' && provider !== 'vk') {
    return res.status(400).json({ error: 'provider должен быть yandex или vk' });
  }
  if (!accessToken) {
    return res.status(400).json({ error: 'Не передан accessToken' });
  }

  try {
    const profile = await fetchProviderProfile(provider, accessToken);
    const user = upsertUser({ provider, ...profile });
    const token = issueSessionToken(user.id);
    res.json({
      token,
      profile: { name: user.name, email: user.email }
    });
  } catch (e) {
    if (e instanceof HttpError) {
      return res.status(e.status).json({ error: e.message });
    }
    console.error('Ошибка входа:', e);
    res.status(502).json({ error: 'Не удалось проверить токен у провайдера' });
  }
});

module.exports = router;
