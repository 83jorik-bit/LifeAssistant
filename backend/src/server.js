require('dotenv').config();

const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const rateLimit = require('express-rate-limit');

const authenticate = require('./middleware/authenticate');
const authRoutes = require('./routes/auth');
const profileRoutes = require('./routes/profile');
const consentRoutes = require('./routes/consent');
const budgetRoutes = require('./routes/budget');
const accountRoutes = require('./routes/account');

const app = express();

app.use(helmet());
app.use(cors()); // API только для мобильного приложения — браузерный CORS ему не мешает.
app.use(express.json({ limit: '2mb' })); // бюджет с сотнями трат легко укладывается в это

// Общий предохранитель от простого перебора/спама поверх более строгого
// лимита на сами маршруты входа (см. routes/auth.js).
app.use(rateLimit({ windowMs: 60_000, limit: 120 }));

app.get('/health', (_req, res) => res.json({ ok: true }));

app.use('/api/auth', authRoutes);
app.use('/api/profile', authenticate, profileRoutes);
app.use('/api/consent', authenticate, consentRoutes);
app.use('/api/budget', authenticate, budgetRoutes);
app.use('/api/account', authenticate, accountRoutes);

app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({ error: 'Внутренняя ошибка сервера' });
});

const port = process.env.PORT || 3000;
app.listen(port, () => {
  console.log(`LifeNavigator backend слушает порт ${port}`);
});
