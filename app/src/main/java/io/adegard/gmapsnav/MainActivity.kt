/**
 * Copyright (c) 2017-2019 Divested Computing Group
 * Copyright (c) 2026 adegard
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.adegard.gmapsnav

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : Activity() {
    private var mapsWebView: WebView? = null
    private var mapsWebSettings: WebSettings? = null
    private var mapsCookieManager: CookieManager? = null
    private val context: Context = this
    private var locationManager: LocationManager? = null

    override fun onPause() {
        super.onPause()
        if (canUseLocation && locationListenerGPS != null) {
            removeLocationListener()
        }
    }

    override fun onResume() {
        super.onResume()

        if (canUseLocation) {
            locationListenerGPS = newLocationListener
            locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0f,
                    locationListenerGPS!!
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = getExternalFilesDir(null)
                if (dir != null) {
                    val f = java.io.File(dir, "crash.txt")
                    f.appendText(
                        "===== " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date()) + " =====\n" +
                            Log.getStackTraceString(throwable) + "\n\n"
                    )
                }
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val urlToLoad = initialUrlFromIntent()

        // Create the WebView
        mapsWebView = findViewById(R.id.mapsWebView)

        // Set cookie options
        mapsCookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(mapsWebView, false)
        }

        // Delete anything from previous sessions
        resetWebView(false)

        // Set the consent cookie to prevent unnecessary redirections
        setConsentCookie()

        // Listen for shared/copied places
        initShareListeners()

        // Wire up the overlay action buttons
        findViewById<android.view.View>(R.id.navFab).setOnClickListener { onNavigateFabClicked() }
        findViewById<android.view.View>(R.id.shareFab).setOnClickListener { onShareFabClicked() }

        // Give location access
        mapsWebView!!.setWebChromeClient(object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                mapLog("[console] ${message.message()}")
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                // Open popups (e.g. place/POI cards) in the same WebView instead
                // of silently dropping them when multi-window is unsupported.
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (canUseLocation) {
                    if (
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (locationRequestCount < 2) { // Don't annoy the user
                            AlertDialog.Builder(context)
                                .setTitle(R.string.title_location_permission)
                                .setMessage(R.string.text_location_permission)
                                .setNegativeButton(
                                    android.R.string.cancel
                                ) { _: DialogInterface?, _: Int ->
                                    // Disable prompts
                                    locationRequestCount = 100
                                }.setPositiveButton(
                                    android.R.string.ok
                                ) { _: DialogInterface?, _: Int ->
                                    // Prompt the user once explanation has been shown
                                    requestPermissions(
                                        arrayOf(
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                            Manifest.permission.ACCESS_FINE_LOCATION
                                        ),
                                        100
                                    )
                                }
                                .create()
                                .show()
                        }
                        locationRequestCount++
                    } else {
                        if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) {
                            Toast.makeText(context, R.string.error_no_gps, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (origin.contains("google.com")) {
                    callback.invoke(origin, true, false)
                }
            }
        })

        mapsWebView!!.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                mapLog("[page] finished: $url")
                val patch = context.assets.open("patch-banner.js").reader(Charsets.UTF_8).use { it.readText() }
                view.evaluateJavascript(patch) {}
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                mapLog("[error] ${request.url} :: ${error.errorCode} ${error.description}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                mapLog("[httpError] ${request.url} :: ${errorResponse.statusCode}")
            }

            // Keep these in sync!
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.url.toString() == "about:blank") {
                    return null
                }
                if (!request.url.toString().startsWith("https://")) {
                    Log.d(
                        TAG,
                        "[shouldInterceptRequest][NON-HTTPS] Blocked access to " + request.url.toString()
                    )
                    return WebResourceResponse(
                        "text/javascript",
                        "UTF-8",
                        null
                    ) // Deny URLs that aren't HTTPS
                }
                var allowed = false
                for (url in allowedDomains) {
                    if (request.url.host == url) {
                        allowed = true
                    }
                }
                for (url in allowedDomainsStart) {
                    if (request.url.host?.startsWith(url) == true) {
                        allowed = true
                    }
                }
                for (url in allowedDomainsEnd) {
                    if (request.url.host?.endsWith(url) == true) {
                        allowed = true
                    }
                }
                if (!allowed) {
                    Log.d(
                        TAG,
                        "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + request.url.host
                    )
                    return WebResourceResponse(
                        "text/javascript",
                        "UTF-8",
                        null
                    ) // Deny URLs not on ALLOWLIST
                }
                for (url in blockedURLs) {
                    if (request.url.toString().contains(url)) {
                        if (request.url.toString().contains("/log204?")) {
                            Log.d(
                                TAG,
                                "[shouldInterceptRequest][ON DENYLIST] Blocked access to a log204 request"
                            )
                        } else {
                            Log.d(
                                TAG,
                                "[shouldInterceptRequest][ON DENYLIST] Blocked access to " + request.url.toString()
                            )
                        }
                        return WebResourceResponse(
                            "text/javascript",
                            "UTF-8",
                            null
                        ) // Deny URLs on DENYLIST
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.toString() == "about:blank") {
                    return false
                }
                when (request.url.scheme) {
                    "tel" -> {
                        startActivity(Intent(Intent.ACTION_DIAL, request.url))
                        return true
                    }
                    "google.navigation" -> {
                        // Hand off to the Google Maps app, or fall back to the
                        // built-in navigation when no external app exists
                        val ssp = request.url.schemeSpecificPart ?: ""
                        val dest = ssp.removePrefix("?").substringBefore("&").removePrefix("q=")
                        val decoded = Uri.decode(dest)
                        if (!launchTurnByTurn(decoded)) {
                            startActivity(NavigationActivity.newIntent(context, decoded))
                        }
                        return true
                    }
                    "waze" -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            return true
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to launch Waze", e)
                        }
                    }
                    "intent" -> {
                        // Maps fires intent:// URLs (e.g. from its "Start" navigation button)
                        val parsed = try {
                            Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME)
                        } catch (e: Exception) {
                            null
                        }
                        if (parsed != null) {
                            try {
                                startActivity(parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                return true
                            } catch (e: Exception) {
                                Log.w(TAG, "Unable to launch intent: " + request.url, e)
                                // Retry forcing the underlying maps link into the Maps app
                                val data = parsed.data
                                if (data != null && data.scheme == "https") {
                                    try {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, data).setPackage(MAPS_PACKAGE)
                                        )
                                        return true
                                    } catch (e2: Exception) {
                                        Log.w(TAG, "Unable to launch maps intent", e2)
                                    }
                                }
                            }
                        }
                        return true // Block the unparseable intent scheme
                    }
                    "geo" -> {
                        // Share location with other map apps without looping back into this one
                        val geoIntent = Intent(Intent.ACTION_VIEW, request.url)
                        if (geoIntent.resolveActivity(packageManager)?.packageName != packageName) {
                            try {
                                startActivity(geoIntent)
                                return true
                            } catch (e: Exception) {
                                Log.w(TAG, "Unable to launch geo intent", e)
                            }
                        }
                    }
                }
                if (!request.url.toString().startsWith("https://")) {
                    Log.d(
                        TAG,
                        "[shouldOverrideUrlLoading][NON-HTTPS] Blocked access to " + request.url.toString()
                    )
                    return true // Deny URLs that aren't HTTPS
                }
                var allowed = false
                for (url in allowedDomains) {
                    if (request.url.host == url) {
                        allowed = true
                    }
                }
                for (url in allowedDomainsStart) {
                    if (request.url.host?.startsWith(url) == true) {
                        allowed = true
                    }
                }
                for (url in allowedDomainsEnd) {
                    if (request.url.host?.endsWith(url) == true) {
                        allowed = true
                    }
                }
                if (!allowed) {
                    Log.d(
                        TAG,
                        "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked access to " + request.url.host
                    )
                    if (request.url.toString().startsWith("https://")) {
                        // FIXME: Soft freeze after closing the dialog
                        AlertDialog.Builder(context)
                            .setTitle(R.string.title_open_link)
                            .setMessage(context.getString(R.string.text_open_link, request.url.toString()))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            }
                            .create()
                            .show()
                    }
                    return true // Deny URLs not on ALLOWLIST
                }
                for (url in blockedURLs) {
                    if (request.url.toString().contains(url)) {
                        mapLog("[denied] " + request.url)
                        return true // Deny URLs on DENYLIST
                    }
                }
                mapLog("[forward] " + request.url)
                return false
            }
        })

        // Set more options
        mapsWebSettings = mapsWebView!!.getSettings()
        // Enable some WebView features
        mapsWebSettings!!.apply {
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
            setSupportMultipleWindows(true)
            // Disable some WebView features
            allowContentAccess = false
            allowFileAccess = false
            builtInZoomControls = false
            databaseEnabled = true
            displayZoomControls = false
            domStorageEnabled = true
            saveFormData = false
            // Change the User-Agent
            userAgentString = USER_AGENT
        }

        // Load Google Maps
        mapsWebView!!.loadUrl(urlToLoad)
    }

    private fun mapLog(msg: String) {
        Log.d(TAG, "[maplog] $msg")
        try {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "maplog.txt")
            f.appendText(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                    " " + msg + "\n"
            )
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resetWebView(true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Credit (CC BY-SA 3.0): https://stackoverflow.com/a/6077173
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (mapsWebView?.canGoBack() == true && mapsWebView?.url != "about:blank") {
                        mapsWebView?.goBack()
                    } else {
                        finish()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun initialUrlFromIntent(): String {
        val intent = intent
        return when {
            intent.action == Intent.ACTION_SEND -> {
                // App was opened from a share sheet
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                sanitizeSharedText(sharedText ?: sharedUri?.toString()) ?: "https://www.google.com/maps"
            }
            else -> {
                val data = intent.data
                when {
                    data?.toString()?.startsWith("https://") == true -> data.toString()
                    data?.toString()?.startsWith("geo:") == true ->
                        "https://www.google.com/maps/place/" + data.toString().substring(4)
                    else -> {
                        Log.d(TAG, "No or Invalid URL passed. Opening homepage instead.")
                        "https://www.google.com/maps"
                    }
                }
            }
        }
    }

    private fun sanitizeSharedText(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        // Prefer an explicit maps link if one is present
        for (line in sharedText.split(Regex("\\s+"))) {
            if (line.startsWith("https://maps.app.goo.gl/") || line.startsWith("https://www.google.com/maps")) {
                return line
            }
        }
        // Otherwise fall back to coordinates
        val match = COORD_REGEX.find(sharedText)
        if (match != null) {
            val latLon = match.groupValues[1] + "," + match.groupValues[2]
            return "https://www.google.com/maps/place/$latLon"
        }
        // Otherwise just search for the shared text
        return "https://www.google.com/maps/search/" + Uri.encode(sharedText)
    }

    /**
     * Reads the currently visible place/query from the maps page via JS.
     */
    private fun readCurrentPlace(callback: (PlaceInfo) -> Unit) {
        val webView = mapsWebView ?: return callback(PlaceInfo("", "", ""))
        val script = "(" +
            "function(){" +
            "var o={query:'',title:'',url:location.href};" +
            "try{var s=document.querySelector('input.searchboxinput');if(s)o.query=s.value;}catch(e){}" +
            "try{var h=document.querySelector('h1');if(h)o.title=h.textContent.trim();}catch(e){}" +
            "return JSON.stringify(o);" +
            "})()"
        webView.evaluateJavascript(script) { json ->
            val place = try {
                val obj = JSONObject(json)
                PlaceInfo(
                    obj.optString("query"),
                    obj.optString("title"),
                    obj.optString("url").ifBlank { webView.url ?: "" }
                )
            } catch (e: Exception) {
                PlaceInfo("", "", webView.url ?: "")
            }
            callback(place)
        }
    }

    private fun onNavigateFabClicked() {
        readCurrentPlace { place ->
            val destination = place.destinationQuery()
            if (destination.isNotEmpty()) {
                startTurnByTurnNavigation(destination)
            } else {
                promptForDestination(place.query)
            }
        }
    }

    private fun onShareFabClicked() {
        readCurrentPlace { place -> sharePlace(place) }
    }

    /**
     * Launches in-app turn-by-turn navigation. First tries the Google Maps app
     * through multiple launch strategies, and falls back to the built-in
     * NavigationActivity when no external navigation app is available.
     */
    private fun startTurnByTurnNavigation(destination: String) {
        val trimmed = destination.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, R.string.error_no_destination, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(NavigationActivity.newIntent(context, trimmed))
    }

    /**
     * Attempts to hand off to the Google Maps app. Returns true if an app
     * handled the intent.
     */
    private fun launchTurnByTurn(destination: String): Boolean {
        val trimmed = destination.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        // Strategy 1: the classic google.navigation scheme (turn-by-turn)
        val navUri = Uri.parse("google.navigation:q=$encoded&mode=d")
        if (tryStart(Intent(Intent.ACTION_VIEW, navUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            return true
        }
        // Strategy 2: same scheme, forced to the Google Maps app
        if (tryStart(
                Intent(Intent.ACTION_VIEW, navUri)
                    .setPackage(MAPS_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        ) {
            return true
        }
        // Strategy 3: dir_action=navigate URL opened inside the Google Maps app
        val dirUri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1" +
                "&destination=$encoded" +
                "&travelmode=driving" +
                "&dir_action=navigate"
        )
        return tryStart(
            Intent(Intent.ACTION_VIEW, dirUri)
                .setPackage(MAPS_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun tryStart(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "[navigation] Could not start $intent", e)
            false
        }
    }

    private fun promptForDestination(prefill: String) {
        val input = EditText(context)
        input.hint = context.getString(R.string.hint_destination)
        if (prefill.isNotBlank()) {
            input.setText(prefill)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.title_enter_destination)
            .setView(input)
            .setPositiveButton(R.string.action_navigate) { _: DialogInterface?, _: Int ->
                startTurnByTurnNavigation(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }

    private fun sharePlace(place: PlaceInfo) {
        val coords = place.coordinates()
        val link = if (coords != null) {
            val (lat, lng) = coords
            "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
        } else {
            place.url
        }
        val name = place.query.trim().ifBlank { place.title.trim() }
        val text = if (name.isNotEmpty()) "$name\n$link" else link
        shareText(text, name.ifEmpty { "Location" })
    }

    private fun shareText(text: String, subject: String = "Location") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, context.getString(R.string.title_share_place)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_installed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun initShareListeners() {
        val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            val clip = clipboard.primaryClip ?: return@addPrimaryClipChangedListener
            if (clip.itemCount == 0) return@addPrimaryClipChangedListener
            val copied = clip.getItemAt(0).coerceToText(context)?.toString() ?: return@addPrimaryClipChangedListener
            handleCopiedText(copied.trim())
        }
    }

    /**
     * When the user copies a coordinates or a maps link (e.g. by tapping the
     * "Share" button inside Google Maps web), offer quick actions.
     */
    private fun handleCopiedText(copied: String) {
        val coordMatch = COORD_REGEX.find(copied)
        val isMapLink = copied.startsWith("https://maps.app.goo.gl/") ||
            copied.startsWith("http://maps.app.goo.gl/") ||
            copied.contains("/maps/place/") ||
            (copied.startsWith("https://www.google.com/maps") && copied.contains("@"))
        if (!isMapLink && coordMatch == null) {
            return
        }
        val destination = if (coordMatch != null) {
            coordMatch.groupValues[1] + "," + coordMatch.groupValues[2]
        } else {
            copied
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.title_copied_place)
            .setMessage(copied)
            .setPositiveButton(R.string.action_share) { _: DialogInterface?, _: Int ->
                shareText(copied)
            }
            .setNeutralButton(R.string.action_navigate) { _: DialogInterface?, _: Int ->
                startTurnByTurnNavigation(destination)
            }
            .setNegativeButton(R.string.action_copy) { _: DialogInterface?, _: Int ->
                // Already on the clipboard, nothing to do
            }
            .create()
            .show()
    }

    private fun resetWebView(exit: Boolean) {
        if (exit) {
            mapsWebView?.loadUrl("about:blank")
            mapsWebView?.removeAllViews()
            mapsWebSettings?.javaScriptEnabled = false
        }
        // mapsWebView.clearCache(true)
        mapsWebView?.apply {
            clearFormData()
            clearHistory()
            clearMatches()
            clearSslPreferences()
        }
        mapsCookieManager?.apply {
            removeSessionCookies(null)
            removeAllCookies(null)
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        if (exit) {
            mapsWebView?.destroyDrawingCache()
            mapsWebView?.destroy()
            mapsWebView = null
        }
    }

    private fun setConsentCookie() {
        // Old method
        /*
        // Way more efficient than SimpleDateFormat
        val cal = Calendar.getInstance()
        // Format: yyyyMMdd
        val consentDate = "${cal[Calendar.YEAR]}${(cal[Calendar.MONTH] + 1).let { month ->
            if (month.toString().length < 2) "0$month" else month
        }}${cal[Calendar.DAY_OF_MONTH].let { day ->
            if (day.toString().length < 2) "0$day" else day
        }}"

        val random = Random()
        val random2digit = random.nextInt(2) + 15
        val random3digit = random.nextInt(999)
        val consentCookie = "YES+cb.$consentDate-$random2digit-p1.en+F+$random3digit"
        mapsCookieManager?.apply {
            setCookie(".google.com", "CONSENT=$consentCookie;")
            // setCookie(".google.com", "CONSENT=PENDING+" + random3digit + ";"); // alternative
            setCookie(".google.com", "ANID=OPT_OUT;")
        }
         */
        mapsCookieManager?.apply {
            setCookie(".google.com", "SOCS=CAI;")
        }
    }

    private val newLocationListener: LocationListener
        get() = object : LocationListener {
            override fun onLocationChanged(location: Location) {
            }

            @Deprecated("")
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
            }

            override fun onProviderEnabled(provider: String) {
            }

            override fun onProviderDisabled(provider: String) {
            }
        }

    private fun removeLocationListener() {
        locationListenerGPS?.let { listener ->
            locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager?.removeUpdates(listener)
        }
        locationListenerGPS = null
    }

    companion object {
        private val allowedDomains = listOf(
            "apis.google.com",
            "consent.google.com",
            "consent.youtube.com", // XXX: Maybe not required?
            "fonts.gstatic.com",
            // "goo.gl",
            "google.com",
            "khms0.google.com",
            "khms1.google.com",
            "khms2.google.com",
            "khms3.google.com",
            "maps.app.goo.gl",
            "maps.google.com",
            "maps.googleapis.com",
            "maps.gstatic.com",
            "mt0.google.com",
            "mt1.google.com",
            "mt2.google.com",
            "mt3.google.com",
            "ssl.gstatic.com",
            "streetviewpixels-pa.googleapis.com",
            "www.google.com",
            "www.gstatic.com"
        )
        private val allowedDomainsStart = listOf(
            "consent.google." // TODO: better cctld handling
        )
        private val allowedDomainsEnd = listOf(
            ".googleusercontent.com"
        )
        private val blockedURLs = listOf(
            // Blocked Domains
            "adservice.google.com",
            "analytics.google.com",
            "app-measurement.com",
            "app-measurement.net",
            "clientmetrics-pa.googleapis.com",
            "csi.gstatic.com",
            "csp.withgoogle.com",
            "doubleclick.com",
            "doubleclick.net",
            "geller-pa.googleapis.com",
            "googleadservices.com",
            "google-analytics.com",
            "google-analytics-cn.com",
            "googletagmanager.com",
            "googletagservices.com",
            "googlesyndication.com",
            "gstaticadssl.l.google.com",
            "notifications-pa.googleapis.com",
            "pagead.l.google.com",
            "pagead2.googlesyndication.com",
            "partnerad.l.google.com",
            "pushnotifications-pa.googleapis.com",
            "region1.google-analytics.com",
            "reminders-pa.googleapis.com",
            "tpc.googlesyndication.com",
            "telemetry-pa.googleapis.com",
            "video-stats.video.google.com",
            "wintricksbanner.googlepages.com",
            "www-google-analytics.l.google.com",
            "youtube-nocookie.com",

            // Blocked URLs
            "google.com/maps/preview/log204",
            "google.com/gen_204",
            "play.google.com/log",
            "/gen_204?",
            "/log204?"
        )

        private const val TAG = "GMapsWV"

        // The official Google Maps app package
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"

        // Anonymous User-Agent used by Vanadium
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/138.0.0.0 Mobile Safari/537.36"

        // Matches "-33.8569,151.2152" or "@-33.8569,-151.2152" style coordinates
        private val COORD_REGEX = Regex("(-?\\d+\\.\\d+),\\s?(-?\\d+\\.\\d+)")

        private var locationListenerGPS: LocationListener? = null
        private val canUseLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        private var locationRequestCount = 0

        fun coordinatesRegex(): Regex = COORD_REGEX
    }
}

/**
 * Describes the currently visible place/query inside the maps page.
 */
data class PlaceInfo(val query: String, val title: String, val url: String) {
    fun coordinates(): Pair<Double, Double>? {
        val match = MainActivity.coordinatesRegex().find(url) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        return lat to lng
    }

    fun destinationQuery(): String {
        coordinates()?.let { (lat, lng) -> return "$lat,$lng" }
        val q = query.trim()
        return if (q.isNotEmpty()) q else title.trim()
    }
}
