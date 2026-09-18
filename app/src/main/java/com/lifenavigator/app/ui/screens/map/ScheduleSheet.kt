package com.lifenavigator.app.ui.screens.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifenavigator.app.data.model.PlacePoint
import com.lifenavigator.app.data.repository.Departure
import com.lifenavigator.app.data.repository.ScheduleResult

/**
 * Расписание станции по данным API Яндекс Расписаний.
 *
 * Обязательное условие использования API — указание Яндекс Расписаний как источника
 * данных, поэтому подпись внизу не украшение, а требование лицензии.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(
    place: PlacePoint,
    isLoading: Boolean,
    result: ScheduleResult?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                place.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            when {
                isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем расписание…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                result is ScheduleResult.Success -> {
                    val schedule = result.schedule
                    Text(
                        buildString {
                            append(schedule.stationTitle)
                            schedule.stationTypeName?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))

                    if (schedule.departures.isEmpty()) {
                        Text(
                            "Рейсов не нашлось ни на сегодня, ни на завтра.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(schedule.departures) { departure -> DepartureRow(departure) }
                        }
                    }

                    schedule.fullScheduleUrl?.let { url ->
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Полное расписание и билеты")
                        }
                    }
                }

                result is ScheduleResult.NotFound -> {
                    Text(
                        "Для этой остановки расписания нет.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Яндекс Расписания знают междугородний транспорт — электрички, поезда, " +
                            "автобусы дальнего следования, паромы и самолёты. Городские автобусы " +
                            "и трамваи туда не входят. Кнопка ниже откроет карту вплотную к остановке: " +
                            "нажмите на её значок — там будет прибытие транспорта.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { YandexMapsLauncher.openPlaceCard(context, place) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Показать остановку на карте Яндекса")
                    }
                }

                result is ScheduleResult.Error -> {
                    Text("Не удалось загрузить расписание.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { YandexMapsLauncher.openPlaceCard(context, place) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Показать остановку на карте Яндекса")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Данные предоставлены сервисом Яндекс Расписания",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DepartureRow(departure: Departure) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(64.dp)) {
            Text(
                departure.time,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (departure.isTomorrow) {
                Text(
                    "завтра",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(departure.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(departure.transportType)
                    departure.number?.let { append(" · $it") }
                    departure.platform?.let { append(" · $it") }
                    departure.days?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
