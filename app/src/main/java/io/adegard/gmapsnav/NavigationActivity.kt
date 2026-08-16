/**
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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class NavigationActivity : Activity() {

    private var navMapWebView: WebView? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var tvInstruction: TextView? = null
    private var tvManeuverDistance: TextView? = null
    private var tvSub: TextView? = null
    private var arrivalPanel: android.view.View? = null
    private var tvArrivalAddress: TextView? = null
    private var hudPanel: android.view.View? = null
    private var controlsPanel: android.view.View? = null
    private var btnBackToMap: ImageButton? = null
    private var btnVoice: ImageButton? = null
    private var followUser = true
    private var headingUp = true
    private var voiceEnabled = true
    private var approachActive = false
    private val speechQueue = ArrayDeque<String>()
    private var mediaPlayer: MediaPlayer? = null
    private var speechBusy = false

    private var destination: Pair<Double, Double>? = null
    private var destinationLabel: String = ""
    private var routePoints: List<Pair<Double, Double>> = emptyList()
    private var cumulative: DoubleArray = DoubleArray(0)
    private var steps: List<NavStep> = emptyList()
    private var routeTotalDistance = 0.0
    private var routeTotalDuration = 0.0
    private var routeLoading = false
    private var routeReady = false
    private var routeGeneration = 0
    private var pageReady = false
    private var currentStepIndex = 0
    private var lastAnnouncedStep = -1
    private var arrived = false
    private var lastPosition: Pair<Double, Double>? = null
    private var lastBearing = 0f
    private var lastLocation: Location? = null

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)
        bindViews()

        tvInstruction?.setText(R.string.nav_status_locating)

        initMapView()
        navMapWebView?.loadUrl("file:///android_asset/nav_map.html")

        initTts()

        val dest = intent.getStringExtra(EXTRA_DESTINATION) ?: ""
        val match = COORD_REGEX.find(dest)
        if (match != null) {
            destination = match.groupValues[1].toDouble() to match.groupValues[2].toDouble()
            destinationLabel = dest.trim()
            maybeFetchRoute()
        } else if (dest.isNotBlank()) {
            tvInstruction?.setText(R.string.nav_status_geocoding)
            geocode(dest)
        } else {
            Toast.makeText(this, R.string.error_no_destination, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ensureLocationPermission()
    }

    private fun bindViews() {
        tvInstruction = findViewById(R.id.tvInstruction)
        tvManeuverDistance = findViewById(R.id.tvManeuverDistance)
        tvSub = findViewById(R.id.tvSub)
        arrivalPanel = findViewById(R.id.arrivalPanel)
        tvArrivalAddress = findViewById(R.id.tvArrivalAddress)
        hudPanel = findViewById(R.id.hudPanel)
        controlsPanel = findViewById(R.id.controlsPanel)
        btnBackToMap = findViewById(R.id.btnBackToMap)
        btnVoice = findViewById(R.id.btnVoice)
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnZoomIn).setOnClickListener {
            evaluateJavascript("window.mapApi.zoomIn();")
        }
        findViewById<ImageButton>(R.id.btnZoomOut).setOnClickListener {
            evaluateJavascript("window.mapApi.zoomOut();")
        }
        findViewById<ImageButton>(R.id.btnCompass).setOnClickListener { toggleHeadingUp() }
        findViewById<ImageButton>(R.id.btnVoice).setOnClickListener { toggleVoice() }
        findViewById<ImageButton>(R.id.btnRecenter).setOnClickListener {
            followUser = true
            evaluateJavascript("window.mapApi.recenter();")
        }
        btnBackToMap?.setOnClickListener {
            navMapWebView?.loadUrl("file:///android_asset/nav_map.html")
        }
        findViewById<ImageButton>(R.id.btnCompass).alpha = if (headingUp) 1f else 0.4f
        updateVoiceButton()
        updateChrome()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initMapView() {
        if (navMapWebView == null) {
            navMapWebView = WebView(this).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    allowContentAccess = false
                    databaseEnabled = true
                    domStorageEnabled = true
                    saveFormData = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    userAgentString = USER_AGENT
                }
                addJavascriptInterface(NavBridge(), "NavBridge")
                setWebViewClient(object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        updateChrome()
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "[navweb] page finished: $url")
                        updateChrome()
                        if (url.startsWith("file://")) {
                            pageReady = true
                            maybeRenderRoute()
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url?.toString() ?: return false
                        Log.d(TAG, "[navweb] override url: $url")
                        if (url.startsWith("google.navigation")) {
                            val ssp = request.url.schemeSpecificPart ?: ""
                            val dest = ssp.removePrefix("?").substringBefore("&").removePrefix("q=")
                            if (dest.isNotEmpty()) {
                                routeToRequested(Uri.decode(dest))
                            }
                            return true
                        }
                        if (!url.startsWith("https://") && !url.startsWith("file://")) {
                            return true
                        }
                        return false
                    }
                })
            }
        }
        val container = findViewById<android.widget.FrameLayout>(R.id.navMap)
        navMapWebView?.let { web ->
            if (web.parent != null) {
                (web.parent as? ViewGroup)?.removeView(web)
            }
            container.addView(
                web,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun updateChrome() {
        val onMap = navMapWebView?.url?.startsWith("file://") != false
        hudPanel?.visibility = if (onMap) android.view.View.VISIBLE else android.view.View.GONE
        controlsPanel?.visibility = if (onMap) android.view.View.VISIBLE else android.view.View.GONE
        btnBackToMap?.visibility = if (onMap) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (navMapWebView == null) return
        setContentView(R.layout.activity_navigation)
        bindViews()
        initMapView()
        arrivalPanel?.visibility =
            if (arrived) android.view.View.VISIBLE else android.view.View.GONE
        val loc = lastLocation
        if (routeReady && loc != null) {
            updateNavigation(loc)
        }
    }

    private fun toggleHeadingUp() {
        headingUp = !headingUp
        evaluateJavascript("window.mapApi.setHeadingMode($headingUp);")
        val compass = findViewById<ImageButton>(R.id.btnCompass)
        compass.alpha = if (headingUp) 1f else 0.4f
        compass.contentDescription = getString(
            if (headingUp) R.string.nav_heading_up else R.string.nav_heading_up_off
        )
    }

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        if (!voiceEnabled) {
            speechQueue.clear()
            try {
                mediaPlayer?.stop()
            } catch (e: Exception) {
            }
            mediaPlayer?.release()
            mediaPlayer = null
            speechBusy = false
        }
        updateVoiceButton()
    }

    private fun updateVoiceButton() {
        btnVoice?.alpha = if (voiceEnabled) 1f else 0.4f
        btnVoice?.setImageResource(
            if (voiceEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
        )
        btnVoice?.contentDescription = getString(
            if (voiceEnabled) R.string.nav_voice_on else R.string.nav_voice_off
        )
    }

    private fun speak(text: String) {
        if (!voiceEnabled) return
        speechQueue.add(text)
        playNextSpeech()
    }

    private fun playNextSpeech() {
        if (speechBusy || speechQueue.isEmpty()) return
        speechBusy = true
        val text = speechQueue.removeFirst()
        try {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (e: Exception) {
            }
            val lang = Locale.getDefault().language.ifBlank { "en" }
            val url = ONLINE_TTS_BASE +
                URLEncoder.encode(text, "UTF-8") + "&tl=" + URLEncoder.encode(lang, "UTF-8")
            mp.setDataSource(url)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                speechBusy = false
                mediaPlayer = null
                try { mp.release() } catch (e: Exception) {}
                playNextSpeech()
            }
            mp.setOnErrorListener { _, _, _ ->
                speechBusy = false
                mediaPlayer = null
                try { mp.release() } catch (e: Exception) {}
                fallbackTts(text)
                playNextSpeech()
                true
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Online TTS failed", e)
            speechBusy = false
            fallbackTts(text)
            playNextSpeech()
        }
    }

    private fun fallbackTts(text: String) {
        if (!voiceEnabled) return
        if (!ttsReady) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed", e)
        }
    }

    private fun routeToRequested(dest: String) {
        handler.post {
            arrived = false
            arrivalPanel?.visibility = android.view.View.GONE
            routeReady = false
            routeLoading = false
            currentStepIndex = 0
            lastAnnouncedStep = -1
            approachActive = false
            val trimmed = dest.trim()
            val match = COORD_REGEX.find(trimmed)
            if (match != null) {
                destination = match.groupValues[1].toDouble() to match.groupValues[2].toDouble()
                destinationLabel = trimmed
            } else {
                destination = null
                destinationLabel = trimmed
            }
            tvArrivalAddress?.text = destinationLabel
            tvInstruction?.setText(R.string.nav_status_loading_route)
            navMapWebView?.loadUrl("file:///android_asset/nav_map.html")
            if (destination == null) {
                if (trimmed.isNotEmpty()) {
                    geocode(trimmed)
                } else {
                    Toast.makeText(this, R.string.error_no_destination, Toast.LENGTH_SHORT).show()
                }
            } else {
                maybeFetchRoute()
            }
        }
    }

    private fun ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                tvInstruction?.setText(R.string.nav_status_no_permission)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        locationListener?.let { listener ->
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager?.removeUpdates(listener)
        }
        locationListener = null
        speechQueue.clear()
        try {
            mediaPlayer?.stop()
        } catch (e: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        speechBusy = false
    }

    override fun onDestroy() {
        super.onDestroy()
        locationListener?.let { listener ->
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager?.removeUpdates(listener)
        }
        speechQueue.clear()
        try {
            mediaPlayer?.stop()
        } catch (e: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "TTS shutdown failed", e)
        }
        navMapWebView?.removeJavascriptInterface("NavBridge")
        navMapWebView?.loadUrl("about:blank")
        navMapWebView = null
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (locationListener != null) return
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onNewLocation(location)
            }

            @Deprecated("")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {
            }

            override fun onProviderDisabled(provider: String) {
            }
        }
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                0f,
                locationListener!!
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "GPS updates denied", e)
        }
        val lastKnown = lastKnownLocation()
        if (lastKnown != null) {
            onNewLocation(lastKnown)
        } else if (destination != null) {
            tvInstruction?.setText(R.string.nav_status_no_fix)
        }
    }

    private fun lastKnownLocation(): Location? {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun onNewLocation(location: Location) {
        lastLocation = location
        lastPosition = location.latitude to location.longitude
        lastBearing = if (location.hasBearing()) location.bearing else lastBearing
        if (navMapWebView != null) {
            evaluateJavascript(
                "window.mapApi.updatePosition(" +
                    "${location.latitude},${location.longitude},$lastBearing);"
            )
        }
        maybeFetchRoute()
        if (routeReady && !arrived) {
            updateNavigation(location)
        }
    }

    private fun maybeFetchRoute() {
        if (routeLoading || routeReady) return
        val start = lastPosition ?: return
        val dest = destination ?: return
        routeLoading = true
        val gen = ++routeGeneration
        tvInstruction?.setText(R.string.nav_status_loading_route)
        fetchRoute(start, dest, gen)
    }

    private fun fetchRoute(start: Pair<Double, Double>, dest: Pair<Double, Double>, gen: Int) {
        Thread {
            try {
                val url = URL(
                    OSRM_BASE + "/route/v1/driving/" +
                        start.second + "," + start.first + ";" +
                        dest.second + "," + dest.first +
                        "?overview=full&geometries=geojson&steps=true&alternatives=false"
                )
                val json = httpGet(url)
                val parsed = parseRoute(json)
                handler.post {
                    if (gen != routeGeneration) {
                        routeLoading = false
                        return@post
                    }
                    if (parsed == null) {
                        routeLoading = false
                        tvInstruction?.setText(R.string.nav_error_route)
                        Toast.makeText(this, R.string.nav_error_route, Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val (points, navSteps, totalDist, totalDur) = parsed
                    routePoints = points
                    cumulative = buildCumulative(points)
                    steps = navSteps.map { it.withAlong(cumulative, points) }
                    routeTotalDistance = totalDist
                    routeTotalDuration = totalDur
                    routeReady = true
                    routeLoading = false
                    currentStepIndex = 0
                    lastAnnouncedStep = -1
                    approachActive = false
                    tvArrivalAddress?.text = destinationLabel
                    maybeRenderRoute()
                    showStep(0, distanceToNextManeuver(0, 0.0))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Route fetch failed", e)
                handler.post {
                    if (gen != routeGeneration) {
                        routeLoading = false
                        return@post
                    }
                    routeLoading = false
                    tvInstruction?.setText(R.string.nav_error_route)
                    Toast.makeText(this, R.string.nav_error_route, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun maybeRenderRoute() {
        if (!pageReady || !routeReady) return
        val arr = JSONArray()
        for (p in routePoints) {
            arr.put(JSONArray().put(p.second).put(p.first))
        }
        val dest = destination ?: return
        evaluateJavascript(
            "window.mapApi.showRoute($arr);" +
                "window.mapApi.setDestination(${dest.second},${dest.first});"
        )
    }

    private fun parseRoute(json: String): RouteData? {
        val obj = JSONObject(json)
        if (obj.optString("code") != "Ok") return null
        val routes = obj.getJSONArray("routes")
        if (routes.length() == 0) return null
        val route = routes.getJSONObject(0)
        val geometry = route.getJSONObject("geometry")
        val coords = geometry.getJSONArray("coordinates")
        val points = ArrayList<Pair<Double, Double>>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            points.add(c.getDouble(1) to c.getDouble(0))
        }
        val legs = route.getJSONArray("legs")
        val navSteps = ArrayList<NavStep>()
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val stepArr = leg.getJSONArray("steps")
            for (si in 0 until stepArr.length()) {
                val s = stepArr.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                navSteps.add(
                    NavStep(
                        lat = loc.getDouble(1),
                        lng = loc.getDouble(0),
                        name = s.optString("name", ""),
                        isArrive = m.optString("type", "") == "arrive",
                        type = m.optString("type", ""),
                        modifier = m.optString("modifier", ""),
                        exit = m.optInt("exit", -1),
                        distance = s.optDouble("distance", 0.0),
                        duration = s.optDouble("duration", 0.0)
                    )
                )
            }
        }
        val totalDist = route.optDouble("distance", 0.0)
        val totalDur = route.optDouble("duration", 0.0)
        return RouteData(points, navSteps, totalDist, totalDur)
    }

    private fun buildCumulative(points: List<Pair<Double, Double>>): DoubleArray {
        val cum = DoubleArray(points.size)
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += haversine(
                points[i - 1].first, points[i - 1].second,
                points[i].first, points[i].second
            )
            cum[i] = sum
        }
        return cum
    }

    private fun progressAlongRoute(lat: Double, lng: Double): Double {
        if (routePoints.size < 2) return 0.0
        var bestDist = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until routePoints.size - 1) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            val (t, d) = projectToSegment(lat, lng, a, b)
            val along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            if (d < bestDist) {
                bestDist = d
                bestAlong = along
            }
        }
        return bestAlong
    }

    private fun projectToSegment(
        lat: Double,
        lng: Double,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>
    ): Pair<Double, Double> {
        val cosLat = max(Math.cos(Math.toRadians((lat + a.first + b.first) / 3.0)), 0.01)
        val ax = a.second * cosLat
        val ay = a.first
        val bx = b.second * cosLat
        val by = b.first
        val px = lng * cosLat
        val py = lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = 0.0
        if (len2 > 0) {
            t = ((px - ax) * dx + (py - ay) * dy) / len2
            if (t < 0) t = 0.0
            if (t > 1) t = 1.0
        }
        val cxp = ax + t * dx
        val cyp = ay + t * dy
        val distDeg = Math.sqrt((px - cxp) * (px - cxp) + (py - cyp) * (py - cyp))
        return t to (distDeg * 111320.0)
    }

    private fun distanceToNextManeuver(stepIndex: Int, progress: Double): Double {
        if (steps.isEmpty()) return 0.0
        val next = stepIndex + 1
        if (next < steps.size) {
            return max(0.0, steps[next].along - progress)
        }
        return max(0.0, routeTotalDistance - progress)
    }

    private fun updateNavigation(location: Location) {
        val progress = progressAlongRoute(location.latitude, location.longitude)

        var idx = currentStepIndex
        while (idx + 1 < steps.size && progress > steps[idx + 1].along - EARLY_TURN_M) {
            idx++
        }
        if (idx != currentStepIndex) {
            currentStepIndex = idx
            approachActive = false
            announce(steps[idx])
        }

        val remaining = max(0.0, routeTotalDistance - progress)
        if (remaining <= ARRIVE_THRESHOLD_M || steps[currentStepIndex].isArrive && progress >= routeTotalDistance - 40) {
            showArrived()
            return
        }

        val step = steps[currentStepIndex]
        val nextManeuver = distanceToNextManeuver(currentStepIndex, progress)
        showStep(currentStepIndex, nextManeuver)

        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).roundToInt() else -1
        val approachDistance = if (speedKmh >= 80) 1000.0 else 150.0
        if (nextManeuver > 1100) {
            approachActive = false
        } else if (currentStepIndex < steps.size - 1 && nextManeuver <= approachDistance && !approachActive) {
            approachActive = true
            val upcoming = steps[currentStepIndex + 1]
            if (!upcoming.isArrive) {
                vibrate()
                speak(getString(R.string.nav_approach, formatDistance(nextManeuver), buildInstruction(upcoming)))
            }
        }

        val fraction = if (routeTotalDistance > 0) remaining / routeTotalDistance else 1.0
        val remDuration = routeTotalDuration * fraction
        val etaText = formatEta(remDuration)
        val speed = speedKmh
        val sub = buildString {
            append(getString(R.string.nav_distance_remaining, formatDistance(remaining)))
            append(" · ")
            append(getString(R.string.nav_time_remaining, etaText))
            if (speed >= 0) {
                append(" · ")
                append(getString(R.string.nav_speed, speed))
            }
        }
        tvSub?.text = sub
    }

    private fun showStep(stepIndex: Int, nextManeuver: Double) {
        val step = steps.getOrNull(stepIndex) ?: return
        tvInstruction?.text = buildInstruction(step)
        tvManeuverDistance?.text = formatDistance(nextManeuver)
    }

    private fun announce(step: NavStep) {
        if (step.isArrive) return
        vibrate()
        speak(buildInstruction(step))
    }

    private fun showArrived() {
        if (arrived) return
        arrived = true
        arrivalPanel?.visibility = android.view.View.VISIBLE
        tvInstruction?.text = getString(R.string.nav_instruction_arrive)
        tvManeuverDistance?.text = ""
        tvSub?.text = destinationLabel
        vibrateArrival()
        speak(getString(R.string.nav_arrived))
        // Re-center on the destination
        evaluateJavascript("window.mapApi.recenter();")
    }

    private fun buildInstruction(step: NavStep): String {
        val road = step.name.ifBlank { getString(R.string.nav_road_unnamed) }
        return when (step.type) {
            "depart" -> getString(R.string.nav_instruction_depart, step.modifier, road)
            "arrive" -> getString(R.string.nav_instruction_arrive)
            "turn", "end of road", "roundabout turn" ->
                getString(R.string.nav_instruction_turn, step.modifier, road)
            "roundabout", "rotary" ->
                if (step.exit > 0) {
                    getString(R.string.nav_instruction_roundabout, step.exit.toString(), road)
                } else {
                    getString(R.string.nav_instruction_keep, step.modifier, road)
                }
            "fork", "merge", "on ramp", "off ramp", "notification" ->
                getString(R.string.nav_instruction_keep, step.modifier, road)
            "continue", "new name" ->
                getString(R.string.nav_instruction_continue, step.modifier, road)
            else -> getString(R.string.nav_instruction_unknown, road)
        }
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters >= 1000 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
            meters >= 100 -> String.format(Locale.US, "%d m", (meters / 10.0).roundToInt() * 10)
            else -> String.format(Locale.US, "%.0f m", meters)
        }
    }

    private fun formatEta(seconds: Double): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.SECOND, seconds.roundToInt())
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
    }

    private fun geocode(query: String) {
        Thread {
            try {
                val url = URL(
                    NOMINATIM + "/search?format=json&limit=1&addressdetails=0&q=" +
                        URLEncoder.encode(query, "UTF-8")
                )
                val json = httpGet(url)
                val arr = JSONArray(json)
                if (arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    val lat = first.optDouble("lat", Double.NaN)
                    val lng = first.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN()) {
                        val label = first.optString("display_name", query)
                        handler.post {
                            destination = lat to lng
                            destinationLabel = label
                            maybeFetchRoute()
                        }
                        return@Thread
                    }
                }
                handler.post {
                    tvInstruction?.setText(R.string.nav_error_geocode)
                    Toast.makeText(this, R.string.nav_error_geocode, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocode failed", e)
                handler.post {
                    tvInstruction?.setText(R.string.nav_error_geocode)
                    Toast.makeText(this, R.string.nav_error_geocode, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun httpGet(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $code for ${url.toExternalForm()}")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun evaluateJavascript(script: String) {
        try {
            navMapWebView?.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Log.w(TAG, "JS call failed", e)
        }
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS init failed", e)
        }
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 150), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 150, 80, 150), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed", e)
        }
    }

    private fun vibrateArrival() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 500), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 150, 300, 150, 500), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed", e)
        }
    }

    private inner class NavBridge {
        @JavascriptInterface
        fun onMapReady() {
            handler.post { pageReady = true; maybeRenderRoute() }
        }

        @JavascriptInterface
        fun setManualMode() {
            followUser = false
        }

        @JavascriptInterface
        fun onDestinationPicked(lat: Double, lng: Double) {
            handler.post {
                destination = lat to lng
                destinationLabel = String.format(Locale.US, "%.5f, %.5f", lat, lng)
                tvArrivalAddress?.text = destinationLabel
                arrived = false
                arrivalPanel?.visibility = android.view.View.GONE
                routeReady = false
                routeLoading = false
                currentStepIndex = 0
                lastAnnouncedStep = -1
                approachActive = false
                followUser = true
                evaluateJavascript("window.mapApi.clearRoute();")
                tvInstruction?.setText(R.string.nav_status_loading_route)
                maybeFetchRoute()
            }
        }
    }

    private data class NavStep(
        val lat: Double,
        val lng: Double,
        val name: String,
        val isArrive: Boolean,
        val type: String,
        val modifier: String,
        val exit: Int,
        val distance: Double,
        val duration: Double,
        val along: Double = 0.0
    ) {
        fun withAlong(
            cumulative: DoubleArray,
            points: List<Pair<Double, Double>>
        ): NavStep {
            val idx = nearestIndexOnPoints(points, lat, lng)
            return copy(along = cumulative.getOrElse(idx) { 0.0 })
        }
    }

    private data class RouteData(
        val points: List<Pair<Double, Double>>,
        val steps: List<NavStep>,
        val totalDistance: Double,
        val totalDuration: Double
    )

    companion object {
        private const val TAG = "GMapsNav"
        private const val OSRM_BASE = "https://router.project-osrm.org"
        private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
        private const val ONLINE_TTS_BASE =
            "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&q="
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/138.0.0.0 Mobile Safari/537.36"
        private const val REQUEST_LOCATION = 100
        private const val EARLY_TURN_M = 30.0
        private const val ARRIVE_THRESHOLD_M = 25.0
        private const val EXTRA_DESTINATION = "io.adegard.gmapsnav.extra.DESTINATION"

        private val COORD_REGEX =
            Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")

        fun newIntent(context: Context, destination: String): Intent {
            return Intent(context, NavigationActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination)
        }
    }
}

private fun nearestIndexOnPoints(
    points: List<Pair<Double, Double>>,
    lat: Double,
    lng: Double
): Int {
    var best = 0
    var bestDist = Double.MAX_VALUE
    for (i in points.indices) {
        val d = haversine(lat, lng, points[i].first, points[i].second)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
