package com.printbook.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.printbook.app.data.db.dao.BookWithCover
import com.printbook.app.data.db.entity.BookStatus
import java.io.File

fun BookWithCover.progress(): Float =
    if (pageCount <= 1) 0f else (lastReadPosition.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "${bytes / 1024} KB"
}

fun BookWithCover.subtitle(): String = buildString {
    append("$pageCount págs.")
    if (status == BookStatus.CAPTURING) append(" · em captura")
    else if (lastReadAt != null) append(" · ${(progress() * 100).toInt()}%")
}

@Composable
fun BookGridItem(book: BookWithCover, coverFile: File?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BookCover(coverFile, book.title, Modifier.fillMaxWidth(), isFavorite = book.isFavorite)
        if (book.lastReadAt != null && book.pageCount > 1) {
            LinearProgressIndicator(progress = { book.progress() }, modifier = Modifier.fillMaxWidth())
        }
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BookListItem(book: BookWithCover, coverFile: File?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(coverFile, book.title, Modifier.width(56.dp), isFavorite = book.isFavorite)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${book.sourceApp.label} · ${book.subtitle()} · ${formatBytes(book.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (book.lastReadAt != null && book.pageCount > 1) {
                LinearProgressIndicator(progress = { book.progress() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
