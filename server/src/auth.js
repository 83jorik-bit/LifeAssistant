// Приложение уже умеет получать access_token напрямую от Яндекса и VK (по PKCE,
// без нашего участия). Сервер не переизобретает OAuth — он лишь ПРОВЕРЯЕТ этот
// токен, обратившись к тому же провайдеру за данными пользователя, и в ответ
// выдаёт свою короткоживущую сессию. Так сервер никогда не видит пароль
// пользователя и не хранит чужие access_token дольше одного запроса.
const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  throw new Error(
    'Не задан JWT_SECRET. Сгенерируйте случайную строку (например: openssl rand -hex 32) ' +
      'и положите в .env — без неё сервер не подписывает сессии.'
  );
}

const SESSION_TTL = '30d';

function issueSessionToken(userId) {
  return jwt.sign({ sub: userId }, JWT_SECRET, { expiresIn: SESSION_TTL });
}

/** Достаёт (id, имя, почта) у провайдера по его access_token. Бросает исключение, если токен недействителен. */
async function fetchProviderProfile(provider, accessToken) {
  if (provider === 'yandex') {
    const response = await fetch('https://login.yandex.ru/info?format=json', {
      headers: { Authorization: `OAuth ${accessToken}` }
    });
    if (!response.ok) throw new Error(`Яндекс отклонил токен (код ${response.status})`);
    const json = await response.json();
    return {
      externalId: String(json.id),
      name: json.real_name || json.display_name || json.login || 'Пользователь Яндекса',
      email: json.default_email || null
    };
  }

  if (provider === 'vk') {
    const response = await fetch('https://id.vk.com/oauth2/user_info', {
      headers: { Authorization: `Bearer ${accessToken}` }
    });
    if (!response.ok) throw new Error(`VK отклонил токен (код ${response.status})`);
    const json = await response.json();
    const user = json.user || json;
    const fullName = [user.first_name, user.last_name].filter(Boolean).join(' ');
    return {
      externalId: String(user.user_id ?? user.id),
      name: fullName || 'Пользователь VK',
      email: user.email || null
    };
  }

  throw new Error(`Неизвестный провайдер: ${provider}`);
}

/** Express-мидлвар: проверяет Authorization: Bearer <наша сессия> и кладёт req.userId. */
function requireAuth(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) {
    return res.status(401).json({ error: 'Нет заголовка Authorization' });
  }
  try {
    const payload = jwt.verify(token, JWT_SECRET);
    req.userId = payload.sub;
    next();
  } catch {
    res.status(401).json({ error: 'Сессия недействительна или истекла' });
  }
}

module.exports = { issueSessionToken, fetchProviderProfile, requireAuth };
