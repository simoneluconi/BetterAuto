package com.simoneluconi.betterauto.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** How the car screen writes dates, times and numbers ([com.simoneluconi.betterauto.settings.AppLocales]). */
val LocalFormatLocale = staticCompositionLocalOf<Locale> { Locale.getDefault() }
