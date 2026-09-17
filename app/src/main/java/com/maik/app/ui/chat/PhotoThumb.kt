package com.maik.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.maik.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoThumb(path: String, size: Dp) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(14.dp)
    val image = bitmap
    if (image == null) {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.photo_in_message),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape)
        )
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
