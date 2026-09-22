const { verifySessionToken } = require('../auth');

/** Требует заголовок Authorization: Bearer <токен сессии>, кладёт req.userId. */
function authenticate(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) {
    return res.status(401).json({ error: 'Нет токена сессии' });
  }
  try {
    req.userId = verifySessionToken(token);
    next();
  } catch (e) {
    res.status(401).json({ error: 'Сессия недействительна или истекла, нужен повторный вход' });
  }
}

module.exports = authenticate;
