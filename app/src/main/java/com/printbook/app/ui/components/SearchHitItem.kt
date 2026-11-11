package com.printbook.app.ui.components

import com.printbook.app.ui.theme.MonoMeta
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.printbook.app.data.db.dao.TextSearchHit

/** Converte o trecho do FTS ("... [termo] ...") em texto com o termo destacado. */
fun highlightSnippet(snippet: String, highlight: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val clean = snippet.replace('\n', ' ')
    while (i < clean.length) {
        val start = clean.indexOf('[', i)
        if (start < 0) { append(clean.substring(i)); break }
        val end = clean.indexOf(']', start + 1)
        if (end < 0) { append(clean.substring(i)); break }
        append(clean.substring(i, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = highlight)) {
            append(clean.substring(start + 1, end))
        }
        i = end + 1
    }
}

@Composable
fun SearchHitItem(hit: TextSearchHit, showBook: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            if (showBook) "${hit.bookTitle} · pág. ${hit.position + 1}" else "Página ${hit.position + 1}",
            style = MonoMeta,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            highlightSnippet(hit.snippet, MaterialTheme.colorScheme.tertiaryContainer),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
    }
}
