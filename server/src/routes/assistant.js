const express = require('express');
const { requireAuth } = require('../auth');

const router = express.Router();
router.use(requireAuth);

const MAX_REQUESTS_PER_MINUTE = 20;
const WINDOW_MS = 60_000;
const requestWindows = new Map();

function isRateLimited(userId) {
  const now = Date.now();
  const key = String(userId);
  const current = requestWindows.get(key);

  if (!current || now - current.startedAt >= WINDOW_MS) {
    requestWindows.set(key, { startedAt: now, count: 1 });
    return false;
  }

  current.count += 1;
  return current.count > MAX_REQUESTS_PER_MINUTE;
}

router.post('/', async (req, res) => {
  const apiKey = process.env.YANDEX_GPT_API_KEY;
  const folderId = process.env.YANDEX_GPT_FOLDER_ID;

  if (!apiKey || !folderId) {
    return res.status(503).json({
      error: 'ИИ-сервис не настроен на сервере'
    });
  }

  const body = req.body || {};
  const systemPrompt = typeof body.systemPrompt === 'string' ? body.systemPrompt.trim() : '';
  const history = body.history;

  if (!systemPrompt || systemPrompt.length > 16_000 || !Array.isArray(history) || history.length > 8) {
    return res.status(400).json({ error: 'Некорректный запрос помощника' });
  }

  const messages = [{ role: 'system', text: systemPrompt }];
  for (const item of history) {
    if (
      !item ||
      !['user', 'assistant'].includes(item.role) ||
      typeof item.text !== 'string' ||
      item.text.trim().length === 0 ||
      item.text.length > 4_000
    ) {
      return res.status(400).json({ error: 'Некорректная история диалога' });
    }
    messages.push({ role: item.role, text: item.text.trim() });
  }

  if (isRateLimited(req.userId)) {
    return res.status(429).json({ error: 'Слишком много запросов к помощнику. Попробуйте позже.' });
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30_000);

  try {
    const upstream = await fetch(
      'https://llm.api.cloud.yandex.net/foundationModels/v1/completion',
      {
        method: 'POST',
        headers: {
          Authorization: `Api-Key ${apiKey}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          modelUri: `gpt://${folderId}/yandexgpt-lite/latest`,
          completionOptions: {
            stream: false,
            temperature: 0.3,
            maxTokens: '600'
          },
          messages
        }),
        signal: controller.signal
      }
    );

    if (!upstream.ok) {
      // Не логируем запрос/контекст пользователя или ответ провайдера: там могут быть данные бюджета.
      console.warn(`YandexGPT upstream returned HTTP ${upstream.status}`);
      return res.status(502).json({ error: 'ИИ-сервис временно недоступен' });
    }

    const result = await upstream.json();
    const text = result?.result?.alternatives?.[0]?.message?.text;

    if (typeof text !== 'string' || !text.trim()) {
      return res.status(502).json({ error: 'ИИ-сервис вернул пустой ответ' });
    }

    return res.json({ text: text.trim() });
  } catch (error) {
    console.warn(`YandexGPT request failed: ${error?.name || 'unknown error'}`);
    return res.status(502).json({ error: 'Не удалось получить ответ от ИИ-сервиса' });
  } finally {
    clearTimeout(timeout);
  }
});

module.exports = router;
