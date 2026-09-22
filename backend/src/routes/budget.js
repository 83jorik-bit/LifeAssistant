const express = require('express');
const db = require('../db');

const router = express.Router();

/**
 * Синхронизация целым снимком — самая простая рабочая стратегия для одного
 * устройства на пользователя. GET отдаёт всё, PUT полностью заменяет всё.
 *
 * Осознанное ограничение: если пользователь войдёт с двух устройств и на обоих
 * будут расхождения, при синхронизации победит тот, кто сохранил последним —
 * настоящего слияния (merge) здесь нет. Для одного телефона, для которого
 * сейчас и построено приложение, это не проблема; для многодетройной работы
 * потребуется трекать версии/изменения по каждой записи отдельно.
 */
router.get('/', (req, res) => {
  const userId = req.userId;

  const income = db.prepare('SELECT month, amount_rub FROM budget_income WHERE user_id = ?').all(userId);
  const limits = db.prepare('SELECT category_key, limit_rub FROM budget_limits WHERE user_id = ?').all(userId);
  const customCategories = db
    .prepare('SELECT key, title FROM budget_custom_categories WHERE user_id = ?')
    .all(userId);
  const expenses = db
    .prepare('SELECT id, category_key, amount_rub, note, date_epoch_day FROM budget_expenses WHERE user_id = ?')
    .all(userId);

  res.json({
    income: Object.fromEntries(income.map((r) => [r.month, r.amount_rub])),
    limits: Object.fromEntries(limits.map((r) => [r.category_key, r.limit_rub])),
    customCategories: customCategories.map((r) => ({ key: r.key, title: r.title })),
    expenses: expenses.map((r) => ({
      id: r.id,
      categoryKey: r.category_key,
      amountRub: r.amount_rub,
      note: r.note,
      dateEpochDay: r.date_epoch_day
    }))
  });
});

router.put('/', (req, res) => {
  const userId = req.userId;
  const body = req.body || {};
  const income = body.income || {};
  const limits = body.limits || {};
  const customCategories = Array.isArray(body.customCategories) ? body.customCategories : [];
  const expenses = Array.isArray(body.expenses) ? body.expenses : [];

  // Всё в одной транзакции: если что-то из четырёх таблиц не запишется,
  // откатываются все — иначе на сервере может остаться наполовину применённый
  // снимок бюджета, что хуже, чем не применить обновление вовсе.
  const replaceAll = db.transaction(() => {
    db.prepare('DELETE FROM budget_income WHERE user_id = ?').run(userId);
    db.prepare('DELETE FROM budget_limits WHERE user_id = ?').run(userId);
    db.prepare('DELETE FROM budget_custom_categories WHERE user_id = ?').run(userId);
    db.prepare('DELETE FROM budget_expenses WHERE user_id = ?').run(userId);

    const insertIncome = db.prepare('INSERT INTO budget_income (user_id, month, amount_rub) VALUES (?, ?, ?)');
    for (const [month, amount] of Object.entries(income)) {
      insertIncome.run(userId, month, amount);
    }

    const insertLimit = db.prepare('INSERT INTO budget_limits (user_id, category_key, limit_rub) VALUES (?, ?, ?)');
    for (const [key, limit] of Object.entries(limits)) {
      insertLimit.run(userId, key, limit);
    }

    const insertCategory = db.prepare(
      'INSERT INTO budget_custom_categories (user_id, key, title) VALUES (?, ?, ?)'
    );
    for (const category of customCategories) {
      insertCategory.run(userId, category.key, category.title);
    }

    const insertExpense = db.prepare(
      `INSERT INTO budget_expenses (user_id, id, category_key, amount_rub, note, date_epoch_day)
       VALUES (?, ?, ?, ?, ?, ?)`
    );
    for (const expense of expenses) {
      insertExpense.run(userId, expense.id, expense.categoryKey, expense.amountRub, expense.note ?? null, expense.dateEpochDay);
    }
  });

  replaceAll();
  res.json({ ok: true });
});

module.exports = router;
