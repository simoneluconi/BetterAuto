package com.simoneluconi.betterauto

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.CarText
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * What BetterAuto is built with and the data it shows, with their licences: each software part opens its full
 * licence text (the ones that ask to travel with the app are in `res/raw`), each data source or service its
 * page. The list in the README says the same.
 */
private class OpenSourcePart(
    val name: String,
    @param:StringRes val purpose: Int,
    val licence: String,
    val url: String,
    @param:RawRes val text: Int? = null,
)

private val Software = listOf(
    OpenSourcePart("aasdk", R.string.licences_aasdk, "GPL-3.0", "https://github.com/opencardev/aasdk"),
    OpenSourcePart("MapLibre Native", R.string.licences_maplibre, "BSD-2-Clause", "https://github.com/maplibre/maplibre-native", R.raw.licence_maplibre),
    OpenSourcePart("Protocol Buffers", R.string.licences_protobuf, "BSD-3-Clause", "https://github.com/protocolbuffers/protobuf", R.raw.licence_protobuf),
    OpenSourcePart("Jetpack Compose, AndroidX, Media3", R.string.licences_androidx, "Apache-2.0", "https://developer.android.com/jetpack/androidx", R.raw.licence_apache_2_0),
    OpenSourcePart("Kotlin, kotlinx.coroutines", R.string.licences_kotlin, "Apache-2.0", "https://kotlinlang.org", R.raw.licence_apache_2_0),
    OpenSourcePart("OkHttp, Okio", R.string.licences_okhttp, "Apache-2.0", "https://square.github.io/okhttp/", R.raw.licence_apache_2_0),
    OpenSourcePart("Shizuku API", R.string.licences_shizuku, "MIT", "https://github.com/RikkaApps/Shizuku-API", R.raw.licence_shizuku_api),
)

private val DataAndServices = listOf(
    OpenSourcePart("OpenStreetMap", R.string.licences_osm, "ODbL", "https://www.openstreetmap.org/copyright"),
    OpenSourcePart("OpenFreeMap, OpenMapTiles", R.string.licences_openfreemap, "© OpenMapTiles", "https://openfreemap.org"),
    OpenSourcePart("Overture Maps", R.string.licences_overture, "ODbL", "https://overturemaps.org"),
    OpenSourcePart("Valhalla", R.string.licences_valhalla, "MIT", "https://github.com/valhalla/valhalla"),
    OpenSourcePart("Photon (komoot)", R.string.licences_photon, "Apache-2.0", "https://photon.komoot.io"),
)

/** The phone app's licences page; a part's full licence opens over it, Back closes it. */
@Composable
internal fun LicencesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val part = Software.firstOrNull { it.name == open }
    if (part?.text != null) {
        BackHandler { open = null }
        LicenceText(part.name, part.text) { open = null }
        return
    }
    PhonePage(stringResource(R.string.licences_title), onBack) {
        CarLabel(stringResource(R.string.licences_intro), 15.sp, colors.text2, maxLines = 6)
        PhoneCard {
            PhoneLinkRow(stringResource(R.string.licences_source), stringResource(R.string.licences_source_desc), "GPL-3.0") {
                openLink(context, SOURCE_URL)
            }
        }
        CarLabel(stringResource(R.string.licences_software), 18.sp, colors.text, weight = FontWeight.Bold)
        PhoneCard {
            Software.forEachIndexed { i, item ->
                if (i > 0) PhoneLine()
                // With its text here when the licence asks to travel with the app, else its own page
                PhoneLinkRow(item.name, stringResource(item.purpose), item.licence) {
                    if (item.text != null) open = item.name else openLink(context, item.url)
                }
            }
        }
        CarLabel(stringResource(R.string.licences_data), 18.sp, colors.text, weight = FontWeight.Bold)
        PhoneCard {
            DataAndServices.forEachIndexed { i, item ->
                if (i > 0) PhoneLine()
                PhoneLinkRow(item.name, stringResource(item.purpose), item.licence) { openLink(context, item.url) }
            }
        }
    }
}

/** A licence's full text, as its authors wrote it. */
@Composable
private fun LicenceText(name: String, @RawRes text: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val body = remember(text) { reflow(context.resources.openRawResource(text).bufferedReader().use { it.readText() }) }
    PhonePage(name, onBack) {
        PhoneCard {
            BasicText(body, style = CarText.copy(color = colors.text2, fontSize = 13.sp, lineHeight = 19.sp))
        }
    }
}

/**
 * The text's own line breaks, made for an 80-column file, joined within each paragraph so the phone wraps it;
 * list items, headings and indented lines keep starting on a line of their own. The words are untouched.
 */
internal fun reflow(text: String): String = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).joinToString("\n\n") { paragraph ->
    paragraph.lines().fold(StringBuilder()) { out, line ->
        val starts = line.isEmpty() || line.first().isWhitespace() || line.startsWith("*") || line.startsWith("-") || line.startsWith("#") ||
            Regex("^\\(?[0-9a-z]{1,2}[.)] ").containsMatchIn(line)
        if (out.isNotEmpty()) out.append(if (starts) "\n" else " ")
        out.append(if (starts) line else line.trim())
    }.toString()
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { EventLog.log("Licences: no app to open a link ($it)") }
}

private const val SOURCE_URL = "https://github.com/simoneluconi/BetterAuto"
