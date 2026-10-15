package com.simoneluconi.betterauto.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.simoneluconi.betterauto.ui.theme.CarText

@Composable
fun CarLabel(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.SemiBold,
    maxLines: Int = 1,
    lineHeight: TextUnit = TextUnit.Unspecified,
    tabularNumbers: Boolean = false,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = CarText.copy(
            color = color,
            fontSize = size,
            fontWeight = weight,
            lineHeight = lineHeight,
            fontFeatureSettings = if (tabularNumbers) "tnum" else null,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
