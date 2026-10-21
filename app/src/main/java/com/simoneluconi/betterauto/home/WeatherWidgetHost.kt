package com.simoneluconi.betterauto.home

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's own weather widget, hosted the way a launcher hosts widgets (no web API of ours): the
 * weather app draws it and keeps it up to date, we only show it in the car. Binding a widget needs the
 * driver's yes once, in a system dialog on the phone ("always allow" makes later ones silent).
 */
object WeatherWidgetHost {
    /** Ours among the app's widget hosts; any fixed number. */
    private const val HOST_ID = 0x5EA7
    private const val PREFS = "weather_widget"
    private const val KEY_ID = "widget_id"
    private const val KEY_CHOSEN = "chosen_provider"

    @Volatile
    private var host: AppWidgetHost? = null
    private var listeners = 0

    private val _changes = MutableStateFlow(0)
    /** Goes up when the widget is bound, set up or another one is chosen: whoever shows it looks again. */
    val changes: StateFlow<Int> = _changes

    fun host(context: Context): AppWidgetHost = host ?: synchronized(this) {
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
    }

    /** Installed weather apps, best first, each with the one widget of it the car shows (see [WeatherProviders]). */
    fun candidates(context: Context): List<AppWidgetProviderInfo> {
        val all = widgets(context)
        val best = WeatherProviders.bestPerApp(all.map(::candidateOf)).map { it.key }.toSet()
        return all.filter { it.provider.flattenToString() in best }
    }

    /** The weather widgets [packageName] offers (its styles), best first. */
    fun stylesOf(context: Context, packageName: String): List<AppWidgetProviderInfo> =
        widgets(context).filter { it.provider.packageName == packageName }

    private fun candidateOf(info: AppWidgetProviderInfo) =
        WidgetCandidate(info.provider.packageName, info.provider.className, "", info.minWidth, info.minHeight)

    /** Every installed weather widget, best first. */
    private fun widgets(context: Context): List<AppWidgetProviderInfo> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
        val pm = context.packageManager
        val infos = runCatching { manager.installedProviders }.getOrDefault(emptyList())
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
        val byKey = infos.associateBy { it.provider.flattenToString() }
        val ranked = WeatherProviders.rank(
            infos.map { WidgetCandidate(it.provider.packageName, it.provider.className, it.loadLabel(pm) ?: "", it.minWidth, it.minHeight) },
            ownPackage = context.packageName,
        )
        return ranked.mapNotNull { byKey[it.key] }
    }

    /** The provider the driver picked on the phone, or null for Auto (the best one). */
    fun chosen(context: Context): String? = prefs(context).getString(KEY_CHOSEN, null)

    /** The widget to show: the chosen one while installed, else the best one; null without any. */
    fun provider(context: Context): AppWidgetProviderInfo? {
        val list = candidates(context)
        // Saved as a widget (the style picked on the phone); an app's best one if an update renamed it
        val chosen = chosen(context)
        return widgets(context).firstOrNull { it.provider.flattenToString() == chosen }
            ?: list.firstOrNull { chosen != null && it.provider.packageName == chosen.substringBefore('/') }
            ?: list.firstOrNull()
    }

    fun choose(context: Context, provider: String?) {
        EventLog.log("Weather widget: provider ${provider ?: "auto"}")
        prefs(context).edit { if (provider == null) remove(KEY_CHOSEN) else putString(KEY_CHOSEN, provider) }
        _changes.value++
    }

    /** The bound widget id for [provider], or null when it still has to be bound (or another was bound). */
    fun boundId(context: Context, provider: AppWidgetProviderInfo): Int? {
        val id = prefs(context).getInt(KEY_ID, -1).takeIf { it >= 0 } ?: return null
        val bound = runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(id) }.getOrNull()
        return id.takeIf { bound?.provider == provider.provider }
    }

    /**
     * Binds [provider] without asking, which works only once the driver ticked "always allow". Returns the
     * id, or null when the system dialog is needed ([bindRequest]).
     */
    fun bindIfAllowed(context: Context, provider: AppWidgetProviderInfo): Int? {
        val id = newId(context)
        val ok = runCatching { AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, provider.provider) }.getOrDefault(false)
        if (!ok) {
            host(context).deleteAppWidgetId(id)
            return null
        }
        onBound(context, id)
        return id
    }

    /** A fresh id and the system dialog that asks the driver to let us show [provider]. */
    fun bindRequest(context: Context, provider: AppWidgetProviderInfo): Pair<Int, Intent> {
        val id = newId(context)
        return id to Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
    }

    fun onBound(context: Context, id: Int) {
        EventLog.log("Weather widget: bound")
        // One widget of ours at a time: drop whatever an earlier choice left behind
        val host = host(context)
        runCatching { host.appWidgetIds }.getOrNull()?.filter { it != id }?.forEach { host.deleteAppWidgetId(it) }
        prefs(context).edit { putInt(KEY_ID, id) }
        _changes.value++
    }

    /** Drops the bound widget, so the next setup binds a fresh one (and the weather app asks its questions again). */
    fun forget(context: Context) {
        val id = prefs(context).getInt(KEY_ID, -1)
        EventLog.log("Weather widget: set up again")
        if (id >= 0) runCatching { host(context).deleteAppWidgetId(id) }
        prefs(context).edit { remove(KEY_ID) }
        _changes.value++
    }

    /** Whether the weather app can change a placed widget's settings in place (Android 9+ feature flag). */
    fun reconfigurable(info: AppWidgetProviderInfo): Boolean =
        info.configure != null && Build.VERSION.SDK_INT >= 28 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0

    fun onBindRefused(context: Context, id: Int) {
        EventLog.log("Weather widget: binding refused")
        host(context).deleteAppWidgetId(id)
    }

    /** The weather app's own setup screen (place, units), when its widget has one that isn't optional. */
    fun needsConfigure(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        return Build.VERSION.SDK_INT < 31 || info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0
    }

    /** Opens it from the phone app; the host starts it, as the weather app may not export it. */
    fun configure(activity: Activity, id: Int) {
        runCatching { host(activity).startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null) }
            .onFailure { EventLog.log("Weather widget: setup screen failed: $it") }
    }

    /**
     * Tells the weather app how big it is drawn (dp), so it picks the matching layout. Only when the size
     * really changed: the app redraws on every call.
     */
    fun resize(context: Context, id: Int, widthDp: Int, heightDp: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val old = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        if (old?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) == widthDp &&
            old.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) == heightDp
        ) {
            return
        }
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            if (Build.VERSION.SDK_INT >= 31) {
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
            }
        }
        runCatching { manager.updateAppWidgetOptions(id, options) }
    }

    /** Updates from the weather app arrive only while someone shows the widget: counted, started once. */
    @Synchronized
    fun startListening(context: Context) {
        if (listeners++ == 0) runCatching { host(context).startListening() }
    }

    @Synchronized
    fun stopListening(context: Context) {
        if (listeners == 0) return
        if (--listeners == 0) runCatching { host(context).stopListening() }
    }

    fun providerKey(info: AppWidgetProviderInfo): String = info.provider.flattenToString()

    private fun newId(context: Context) = host(context).allocateAppWidgetId()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val REQUEST_CONFIGURE = 0x5EA7
}
