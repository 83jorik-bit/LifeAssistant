package com.lifenavigator.app.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifenavigator.app.data.auth.OAuthProvider

/**
 * Кнопки входа в фирменном виде провайдеров.
 *
 * У обоих сервисов гайдлайны прямо запрещают менять цвет кнопки и её содержимое.
 *
 * VK ID: цвет подтверждён официальной документацией — Azure/A100, #0077FF, текст белый.
 * Альтернатива при конфликте с дизайном — белая кнопка (#FFFFFF), других цветов нет.
 * Пробел между «VK» и «ID» в тексте обязателен.
 *
 * Яндекс ID: гайдлайн (yandex.ru/dev/id/doc/ru/codes/buttons-design) тоже запрещает менять
 * цвет и содержимое, НО отдаёт готовую кнопку через визуальный конструктор — точного HTML/HEX
 * кода в документации в текстовом виде нет, только генератор. Поэтому ниже — узнаваемое
 * приближение (чёрная кнопка, белый текст, красный кружок с «Я» — текущие цвета логотипа
 * Яндекса), а не пиксель-в-пиксель их сгенерированный код. Если для публикации важна точность
 * до пикселя, кнопку стоит собрать в их конструкторе и вставить как есть.
 */
@Composable
fun BrandedSignInButton(
    provider: OAuthProvider,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    when (provider) {
        OAuthProvider.VK -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0077FF),
                contentColor = Color.White
            )
        ) {
            VkMark(color = Color.White)
            Spacer(Modifier.width(10.dp))
            // Пробел между VK и ID в тексте кнопки — требование гайдлайна, не опечатка.
            Text("Войти через VK ID", fontWeight = FontWeight.Medium)
        }

        OAuthProvider.YANDEX -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = Color.White
            )
        ) {
            YandexMark()
            Spacer(Modifier.width(10.dp))
            Text("Войти с Яндекс ID", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun VkMark(color: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Text("VK", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun YandexMark() {
    // Красный кружок с белой «Я» — текущий фирменный знак Яндекса.
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(0xFFFC3F1D)),
        contentAlignment = Alignment.Center
    ) {
        Text("Я", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
