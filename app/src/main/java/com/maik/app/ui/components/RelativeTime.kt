package com.maik.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.maik.app.R
import com.maik.app.ui.theme.*
import com.maik.app.data.Ago
import com.maik.app.data.ago
import java.text.DateFormat
import java.util.Date

/** "now", "14m", "3h", "2d", or a short date — translated, with the locale's date order. */
@Composable
fun relativeTime(at: Long): String = when (val a = ago(at)) {
    Ago.Now -> stringResource(R.string.time_now)
    is Ago.Minutes -> pluralStringResource(R.plurals.time_minutes, a.count.toInt(), a.count)
    is Ago.Hours -> pluralStringResource(R.plurals.time_hours, a.count.toInt(), a.count)
    is Ago.Days -> pluralStringResource(R.plurals.time_days, a.count.toInt(), a.count)
    is Ago.On -> {
        val locale = LocalConfiguration.current.locales[0]
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(a.at))
    }
}
