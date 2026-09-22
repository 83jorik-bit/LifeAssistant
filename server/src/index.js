require('dotenv').config();

const express = require('express');
const cors = require('cors');

const authRoutes = require('./routes/auth');
const profileRoutes = require('./routes/profile');
const consentRoutes = require('./routes/consent');
const budgetRoutes = require('./routes/budget');
const accountRoutes = require('./routes/account');

const app = express();

// 1 МБ достаточно с большим запасом: самое тяжёлое, что сюда прилетает, — подпись
// согласия (нарисованная пальцем линия в PNG), а не фотографии.
app.use(express.json({ limit: '1mb' }));
app.use(cors());

app.get('/health', (req, res) => res.json({ ok: true }));

app.use('/api/auth', authRoutes);
app.use('/api/profile', profileRoutes);
app.use('/api/consent', consentRoutes);
app.use('/api/budget', budgetRoutes);
app.use('/api/account', accountRoutes);

// Единая обработка ошибок — чтобы исключение в обработчике маршрута не роняло
// весь процесс, а превращалось в аккуратный 500 с сообщением в лог сервера,
// не в ответ клиенту (чтобы не утекали детали реализации).
app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ error: 'Внутренняя ошибка сервера' });
});

const port = process.env.PORT || 3000;
app.listen(port, () => {
  console.log(`LifeNavigator server слушает порт ${port}`);
});
