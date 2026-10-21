package com.simoneluconi.betterauto.smarthome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.MainActivity

/**
 * The sign-in plumbing every OAuth provider shares, without knowing any service: each provider's client
 * id is its page on the project's GitHub Pages (gh-pages branch, `auth/<provider id>/`), which declares
 * the app's redirect `betterauto://auth/<provider id>` with a `<link rel="redirect_uri">`.
 */
object SmartHomeAuth {
    /** Where the per-service client id pages live. */
    const val PAGES_BASE = "https://simoneluconi.github.io/BetterAuto/auth/"
    const val SCHEME = "betterauto"
    const val HOST = "auth"

    fun clientId(providerId: String) = "$PAGES_BASE$providerId/"

    fun redirectUri(providerId: String) = "$SCHEME://$HOST/$providerId"

    /**
     * Opens a service's sign-in page as a Custom Tab inside the app's own task (the extra alone, no library),
     * so that coming back to the app closes it instead of leaving the browser on the login page. Browsers
     * without Custom Tabs ignore the extra and open a normal tab.
     */
    fun openSignIn(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/**
 * Where the browser comes back after a service's sign-in (betterauto://auth/<provider id>?…): hands the
 * query to that provider ([SmartHomeProvider.onSignInAnswer]) and goes back to the app.
 */
class SignInActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SmartHome.init(this)
        val uri = intent?.data
        val providerId = uri?.pathSegments?.firstOrNull()
        val provider = SmartHome.provider(providerId)
        if (uri == null || provider == null) {
            EventLog.log("Smart home: sign-in answer for an unknown service, ignored")
        } else {
            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
            provider.onSignInAnswer(params)
        }
        // Back where the sign-in started: the app's Smart home page, not its home page; CLEAR_TOP closes the tab
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SMART_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
