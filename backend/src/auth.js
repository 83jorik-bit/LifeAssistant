const jwt = require('jsonwebtoken');
const db = require('./db');

const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  throw new Error('JWT_SECRET не задан. Без него сервер не может подписывать сессии — см. .env.example');
}
const JWT_TTL = '30d';

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/**
 * Проверяет access_token у самого провайдера и достаёт профиль.
 *
 * Важно: мы не доверяем тому, что прислал клиент, — мы спрашиваем у Яндекса/VK
 * напрямую, чей это токен. Если токен поддельный или просроченный, провайдер
 * сам откажет, и наш сервер узнает об этом раньше, чем создаст пользователя.
 *
 * client_secret здесь не нужен — то же самое рассуждение, что и в мобильном
 * приложении (см. PkceOAuthClient.kt): проверка по access_token через
 * userinfo-эндпоинт провайдера его не требует.
 */
async function fetchProviderProfile(provider, accessToken) {
  if (provider === 'yandex') {
    const response = await fetch('https://login.yandex.ru/info?format=json', {
      headers: { Authorization: `OAuth ${accessToken}` }
    });
    if (!response.ok) throw new HttpError(401, 'Яндекс не подтвердил токен');
    const json = await response.json();
    return {
      providerUserId: String(json.id),
      name: json.real_name || json.display_name || json.login,
      email: json.default_email || null
    };
  }

  if (provider === 'vk') {
    const response = await fetch('https://id.vk.com/oauth2/user_info', {
      headers: { Authorization: `Bearer ${accessToken}` }
    });
    if (!response.ok) throw new HttpError(401, 'VK не подтвердил токен');
    const json = await response.json();
    const user = json.user || json;
    const name = [user.first_name, user.last_name].filter(Boolean).join(' ') || 'Пользователь VK';
    return {
      providerUserId: String(user.user_id ?? user.id),
      name,
      email: user.email || null
    };
  }

  throw new HttpError(400, `Неизвестный провайдер: ${provider}`);
}

/** Находит пользователя по (провайдер, id у провайдера) или заводит нового. */
function upsertUser({ provider, providerUserId, name, email }) {
  const existing = db
    .prepare('SELECT * FROM users WHERE provider = ? AND provider_user_id = ?')
    .get(provider, providerUserId);

  if (existing) {
    // Имя и почта могли смениться на стороне провайдера — обновляем при каждом входе.
    db.prepare('UPDATE users SET name = ?, email = ? WHERE id = ?').run(name, email, existing.id);
    return { ...existing, name, email };
  }

  const result = db
    .prepare('INSERT INTO users (provider, provider_user_id, name, email) VALUES (?, ?, ?, ?)')
    .run(provider, providerUserId, name, email);
  return { id: result.lastInsertRowid, provider, provider_user_id: providerUserId, name, email };
}

function issueSessionToken(userId) {
  return jwt.sign({ sub: userId }, JWT_SECRET, { expiresIn: JWT_TTL });
}

function verifySessionToken(token) {
  const payload = jwt.verify(token, JWT_SECRET);
  return payload.sub;
}

module.exports = { fetchProviderProfile, upsertUser, issueSessionToken, verifySessionToken, HttpError };
