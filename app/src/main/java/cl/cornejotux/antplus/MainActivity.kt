package cl.cornejotux.antplus

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.hardware.usb.UsbManager
import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import android.location.Location
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.provider.MediaStore
import java.io.OutputStream
import android.os.Handler
import android.os.Looper
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var bpm: TextView
    private lateinit var messageIndicator: TextView
    private lateinit var chart: HeartRateChartView
    private var usageMode = false
    private var heartRateHandle: PccReleaseHandle<AntPlusHeartRatePcc>? = null
    private var bleClient: BleHeartRateClient? = null
    private val store by lazy { LocalStore(this) }
    private var activeRecord: ActivityRecord? = null
    private var locationTracker: LocationTracker? = null
    private var mapView: MapView? = null
    private var routeLine: Polyline? = null
    private var currentMarker: Marker? = null
    private var goButton: TextView? = null
    private var gpsActive = false
    private var hrmActive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var activityStartedAt = 0L
    private var distanceMeters = 0.0
    private var lastLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.navigationBarColor = android.graphics.Color.WHITE
        window.isNavigationBarContrastEnforced = true
        showWelcome()
        if (intent.getBooleanExtra("STOP_RECORD", false)) finishActivityPrompt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("STOP_RECORD", false)) finishActivityPrompt()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && !usageMode) showSetup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 11 && hasLocationPermission()) startLocationTracking()
    }

    private fun showSetup() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        val title = TextView(this).apply { text = "Configuración inicial"; textSize = 28f }
        status = TextView(this).apply { text = "Comprobando requisitos..."; textSize = 18f }
        bpm = TextView(this).apply { text = "-- BPM"; textSize = 48f; setPadding(0, 48, 0, 48) }
        val detector = AntSupportDetector(this@MainActivity)
        val details = TextView(this).apply { text = detector.setupReport(); textSize = 16f }
        val radio = installButton("Instalar ANT Radio Service", "com.dsi.ant.service.socket")
        radio.visibility = if (detector.hasRadioService()) android.view.View.GONE else android.view.View.VISIBLE
        val usb = installButton("Instalar ANT USB Service (opcional)", "com.dsi.ant.usbservice")
        usb.visibility = if (detector.hasUsbServiceInstalled()) android.view.View.GONE else android.view.View.VISIBLE
        val plugins = installButton("Instalar ANT+ Plugins", "com.dsi.ant.plugins.antplus")
        plugins.visibility = if (detector.hasPluginsInstalled()) android.view.View.GONE else android.view.View.VISIBLE
        val continueButton = Button(this).apply {
            text = "Continuar a la pantalla de uso"
            isEnabled = detector.isReady()
            setOnClickListener { showMenu() }
        }
        status.text = if (detector.isReady()) "Requisitos principales OK. USB Service es opcional." else "Faltan componentes principales."
        root.addView(title); root.addView(status); root.addView(details)
        root.addView(radio); root.addView(usb); root.addView(plugins); root.addView(continueButton)
        setContentView(root)
    }

    private fun showWelcome() {
        usageMode = false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(40, 40, 40, 40); background = BitmapDrawable(resources, BitmapFactory.decodeResource(resources, R.drawable.freerun_welcome)).apply { gravity = android.view.Gravity.FILL } }
        root.addView(TextView(this).apply { text = "FreeRUN"; textSize = 38f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = "Registra tus actividades.\nConserva tus datos."; textSize = 21f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 24, 0, 24) })
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; layoutParams = LinearLayout.LayoutParams(-1, 12) }
        root.addView(progress)
        setContentView(root)
        val start = System.currentTimeMillis()
        uiHandler.post(object : Runnable { override fun run() { val elapsed = System.currentTimeMillis() - start; progress.progress = ((elapsed * 100) / 4000L).toInt().coerceAtMost(100); if (elapsed < 4000L) uiHandler.postDelayed(this, 40) else showMenu() } })
    }

    private fun showMenu() {
        usageMode = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 52, 28, 0); setBackgroundColor(android.graphics.Color.rgb(15, 35, 65)) }
        root.addView(TextView(this).apply { text = "FreeRUN"; textSize = 30f; gravity = Gravity.CENTER; includeFontPadding = true; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.WHITE); layoutParams = LinearLayout.LayoutParams(-1, 120).apply { topMargin = 12 } })
        val go = TextView(this).apply {
            text = "GO"
            textSize = 30f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 42f; setColor(android.graphics.Color.rgb(46, 125, 50)) }
            translationY = -360f
            val sideMargin = (resources.displayMetrics.widthPixels * 0.025f).toInt()
            layoutParams = LinearLayout.LayoutParams(-1, 180).apply { leftMargin = sideMargin; rightMargin = sideMargin; topMargin = 18; bottomMargin = 10 }
            setOnClickListener { chooseActivityType() }
        }
        goButton = go
        root.addView(LinearLayout(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(go)
        root.addView(navBar())
        protectBottomInset(root)
        setContentView(root)
    }

    private fun chooseActivityType() {
        val types = arrayOf("🏃  Correr", "🚶  Caminar", "🥾  Hike", "🚴  Bicicleta")
        AlertDialog.Builder(this).setTitle("¿Qué actividad harás?")
            .setItems(types) { _, which -> requestRecordStart(types[which].substringAfter("  ")) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun protectBottomInset(view: View) {
        // La ventana ya respeta la barra nativa; no agregamos padding adicional
        // porque reduce y recorta la barra propia de FreeRUN.
    }

    private fun navBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); background = android.graphics.drawable.ColorDrawable(android.graphics.Color.argb(128, 255, 255, 255)); elevation = 16f; translationY = -170f; layoutParams = LinearLayout.LayoutParams(-1, 180).apply { leftMargin = -28; rightMargin = -28 }
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.bottomMargin = 0
            view.layoutParams = params
            view.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(this)
        addView(navItem("⌂\nInicio") { showMenu() }); addView(navItem("▤\nHistorial") { showHistory() }); addView(navItem("▥\nEstadísticas") { showStatistics() }); addView(navItem("⚙\nConfiguración") { showSettings() })
    }

    private fun showStatistics(period: String = "Total") {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 0); setBackgroundColor(android.graphics.Color.WHITE) }
        root.addView(TextView(this).apply { text = "Estadísticas"; textSize = 30f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(85, 52, 125)) })
        val selector = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 18, 0, 18) }
        listOf("Semana", "Mes", "Año", "Total").forEach { item -> selector.addView(Button(this).apply { text = item; textSize = 15f; isAllCaps = false; setOnClickListener { showStatistics(item) }; backgroundTintList = ColorStateList.valueOf(if (item == period) android.graphics.Color.rgb(105, 65, 145) else android.graphics.Color.WHITE); setTextColor(if (item == period) android.graphics.Color.WHITE else android.graphics.Color.rgb(70, 65, 80)); layoutParams = LinearLayout.LayoutParams(0, 58, 1f).apply { setMargins(3, 0, 3, 0) } }) }
        root.addView(selector)
        val now = System.currentTimeMillis()
        val range = when (period) { "Semana" -> now - 7L * 24 * 60 * 60 * 1000; "Mes" -> now - 30L * 24 * 60 * 60 * 1000; "Año" -> now - 365L * 24 * 60 * 60 * 1000; else -> 0L }
        val records = store.list().filter { range == 0L || it.start >= range }
        val km = records.sumOf { it.routeDistanceKm() }
        val time = records.sumOf { it.durationMs }
        val maxSpeed = records.mapNotNull { record -> if (record.durationMs > 0) record.routeDistanceKm() / (record.durationMs / 3600000.0) else null }.maxOrNull() ?: 0.0
        root.addView(TextView(this).apply { text = if (period == "Total") "Todo el tiempo" else "Resumen de $period"; textSize = 22f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(55, 52, 70)); setPadding(0, 10, 0, 10) })
        root.addView(ActivityChartView(this, records, period).apply { layoutParams = LinearLayout.LayoutParams(-1, 300).apply { setMargins(0, 8, 0, 18) } })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(a: String, b: String) { val r = LinearLayout(this); r.addView(statTile(a, if (a == "Distancia") "%.2f km".format(km) else if (a == "Tiempo total") formatDuration(time) else if (a == "Velocidad máxima") "%.1f km/h".format(maxSpeed) else records.size.toString())); r.addView(statTile(b, if (b == "Distancia") "%.2f km".format(km) else if (b == "Tiempo total") formatDuration(time) else if (b == "Velocidad máxima") "%.1f km/h".format(maxSpeed) else records.size.toString())); grid.addView(r) }
        row("Distancia", "Tiempo total"); row("Velocidad máxima", "Actividades")
        root.addView(grid); root.addView(TextView(this).apply { text = "Resumen por $period\nCada actividad queda guardada localmente y puede exportarse desde Historial."; textSize = 16f; setTextColor(android.graphics.Color.LTGRAY); setPadding(8, 24, 8, 8) }); root.addView(LinearLayout(this), LinearLayout.LayoutParams(1, 0, 1f)); root.addView(navBar()); protectBottomInset(root); setContentView(root)
    }

    private fun statTile(label: String, value: String) = TextView(this).apply { text = "$value\n$label"; textSize = 18f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(15, 35, 65)); background = GradientDrawable().apply { cornerRadius = 24f; setColor(android.graphics.Color.WHITE) }; layoutParams = LinearLayout.LayoutParams(0, 150, 1f).apply { setMargins(6, 6, 6, 6) } }

    private class ActivityChartView(context: Context, private val records: List<ActivityRecord>, private val period: String) : View(context) {
        private val bg = android.graphics.Paint(1).apply { color = android.graphics.Color.rgb(246, 246, 252) }
        private val bar = android.graphics.Paint(1).apply { color = android.graphics.Color.rgb(105, 65, 145) }
        private val text = android.graphics.Paint(1).apply { color = android.graphics.Color.rgb(65, 60, 80); textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas); canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 28f, 28f, bg)
            canvas.drawText("Actividades en el tiempo", 28f, 42f, text)
            val chartTop = 70f; val chartBottom = height - 45f; val count = records.size.coerceAtLeast(1); val slot = (width - 56f) / count
            records.forEachIndexed { index, record ->
                val value = if (record.durationMs > 0) record.routeDistanceKm().coerceAtLeast(0.2) else 0.2
                val max = records.maxOfOrNull { it.routeDistanceKm().coerceAtLeast(0.2) } ?: 1.0
                val barHeight = ((chartBottom - chartTop - 10) * (value / max)).toFloat()
                val left = 28f + index * slot + slot * .25f
                canvas.drawRoundRect(left, chartBottom - barHeight, left + slot * .5f, chartBottom, 12f, 12f, bar)
            }
            canvas.drawText(if (period == "Total") "Todas las actividades" else "Actividades de $period", 28f, height - 15f, text)
        }
    }

    private fun statusIndicator(icon: String, label: String, active: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(18, 8, 18, 8); setOnClickListener { action() }
        val circle = TextView(context).apply { text = icon; textSize = 28f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (active) android.graphics.Color.rgb(46, 125, 50) else android.graphics.Color.rgb(210, 55, 55)); setStroke(5, if (active) android.graphics.Color.rgb(46, 125, 50) else android.graphics.Color.rgb(210, 55, 55)) }; layoutParams = LinearLayout.LayoutParams(72, 72) }
        addView(circle); addView(TextView(context).apply { text = if (active) "$label activo" else "$label esperando"; textSize = 12f; setTextColor(android.graphics.Color.WHITE) })
    }

    private fun navItem(label: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(8, 4, 8, 4)
        setOnClickListener { action() }
        val parts = label.split("\n")
        addView(TextView(context).apply {
            text = parts.first()
            gravity = Gravity.CENTER
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.rgb(25, 72, 125))
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, 96))
        addView(TextView(context).apply {
            text = parts.last()
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.rgb(25, 72, 125))
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, 48))
        layoutParams = LinearLayout.LayoutParams(0, 180, 1f)
    }

    private fun actionCard(title: String, subtitle: String, primary: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20); setOnClickListener { action() }
        background = GradientDrawable().apply { cornerRadius = 28f; setColor(if (primary) android.graphics.Color.rgb(25, 72, 125) else android.graphics.Color.WHITE) }
        elevation = 5f
        addView(TextView(context).apply { text = title; textSize = if (primary) 22f else 18f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(if (primary) android.graphics.Color.WHITE else android.graphics.Color.rgb(15, 35, 65)) })
        addView(TextView(context).apply { text = subtitle; textSize = 14f; setPadding(0, 6, 0, 0); setTextColor(if (primary) android.graphics.Color.rgb(225, 235, 247) else android.graphics.Color.DKGRAY) })
        layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14 }
    }

    private fun menuButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(android.graphics.Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.rgb(25, 72, 125))
        setPadding(24, 18, 24, 18)
        setOnClickListener { action() }
    }

    private fun showNewRecord() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48) }
        root.addView(TextView(this).apply { text = "Nuevo registro"; textSize = 28f })
        root.addView(TextView(this).apply { text = "La sesión guardará tiempo, distancia y ruta GPS."; textSize = 17f })
        root.addView(menuButton("Iniciar nuevo registro") { beginRecord("actividad") })
        root.addView(navBar())
        protectBottomInset(root)
        setContentView(root)
    }

    private fun beginRecord(type: String) {
        activeRecord = ActivityRecord(System.currentTimeMillis(), type, System.currentTimeMillis(), 0, emptyList())
        showRecordingNotification()
        showCountdown()
    }

    private fun requestRecordStart(type: String) {
        if (gpsActive && hasLocationPermission()) {
            beginRecord(type)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("GPS no disponible")
            .setMessage("Puedes esperar hasta recibir señal GPS o iniciar sin GPS. Sin GPS se registrarán el tiempo y la frecuencia cardíaca si está disponible.")
            .setPositiveButton("Esperar señal") { _, _ ->
                if (!hasLocationPermission()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
                else Toast.makeText(this, "Esperando señal GPS…", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Iniciar sin GPS") { _, _ -> beginRecord(type) }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun showRecordingNotification() {
        val channelId = "freerun_recording"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(channelId, "Registro FreeRUN", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = Intent(this, MainActivity::class.java).putExtra("STOP_RECORD", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(this, 77, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder.setSmallIcon(R.drawable.ic_location_dot)
            .setContentTitle("FreeRUN está registrando")
            .setContentText("La actividad sigue activa en segundo plano")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "PARAR REGISTRO", pending).build())
            .build()
        manager.notify(77, notification)
    }

    private fun clearRecordingNotification() {
        getSystemService(NotificationManager::class.java).cancel(77)
    }

    private fun showCountdown() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(android.graphics.Color.rgb(15, 35, 65)) }
        val counter = TextView(this).apply { text = "3"; textSize = 104f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER }
        root.addView(TextView(this).apply { text = "Prepárate"; textSize = 24f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER })
        root.addView(counter); setContentView(root)
        listOf("3", "2", "1", "GO").forEachIndexed { index, value -> uiHandler.postDelayed({ counter.text = value }, index * 1000L) }
        uiHandler.postDelayed({
            activityStartedAt = System.currentTimeMillis(); distanceMeters = 0.0; lastLocation = null
            if (!hasLocationPermission()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11) else startLocationTracking()
            showUsage()
        }, 4000L)
    }

    private fun startLocationTracking() {
        locationTracker = LocationTracker(this) { location: Location ->
            activeRecord?.let { record ->
                val point = GpsPoint(System.currentTimeMillis(), location.latitude, location.longitude, location.accuracy)
                activeRecord = record.copy(route = record.route + point)
                lastLocation?.let { distanceMeters += it.distanceTo(location).toDouble() }; lastLocation = location
                gpsActive = true
                runOnUiThread { goButton?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(android.graphics.Color.rgb(46, 125, 50)) } }
                runOnUiThread {
                    mapView?.let { map ->
                        val geo = GeoPoint(location.latitude, location.longitude)
                        if (routeLine == null) { routeLine = Polyline().apply { color = android.graphics.Color.rgb(25, 72, 125); width = 9f }; map.overlays.add(routeLine) }
                        routeLine?.addPoint(geo)
                        routeLine?.let { line ->
                            val points = line.points
                            if (points.size == 1) map.controller.setZoom(19.0)
                            else if (points.size > 1) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 110)
                        }
                        map.controller.animateTo(geo); map.invalidate()
                        if (currentMarker == null) { currentMarker = Marker(map).apply { title = "Posición actual"; icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_location_dot) }; map.overlays.add(currentMarker) }
                        currentMarker?.position = geo; currentMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); map.invalidate()
                    }
                }
            }
        }
        locationTracker?.start()
    }

    private fun showHistory(filter: String = "Todas") {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 0); setBackgroundColor(android.graphics.Color.rgb(15, 35, 65)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply { text = "Registros realizados"; textSize = 30f; setTypeface(null, android.graphics.Typeface.BOLD) })
        val selector = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14, 0, 14) }
        listOf("Todas", "Correr", "Caminar", "Hike", "Bicicleta").forEach { item -> selector.addView(Button(this).apply { text = item; textSize = 10f; isAllCaps = false; setOnClickListener { showHistory(item) }; backgroundTintList = ColorStateList.valueOf(if (item == filter) android.graphics.Color.rgb(46, 125, 50) else android.graphics.Color.rgb(80, 105, 135)); setTextColor(android.graphics.Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, 50, 1f).apply { setMargins(2, 0, 2, 0) } }) }
        content.addView(selector)
        val records = store.list().filter { filter == "Todas" || it.type == filter }
        if (records.isEmpty()) content.addView(TextView(this).apply { text = "Todavía no hay registros."; textSize = 18f })
        if (records.isNotEmpty()) content.addView(Button(this).apply { text = "Descargar todas en KML"; setOnClickListener { chooseExport(records, "freerun_actividades.kml") } })
        records.forEach { record ->
            val date = DateFormat.getDateTimeInstance().format(Date(record.start))
            content.addView(actionCard(date, "Duración: ${formatDuration(record.durationMs)}   ·   Distancia: ${"%.2f".format(record.routeDistanceKm())} km", false) { showRecordDetail(record) })
        }
        root.addView(ScrollView(this).apply { addView(content); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })
        root.addView(navBar()); protectBottomInset(root); setContentView(root)
    }

    private fun chooseExport(records: List<ActivityRecord>, filename: String) {
        AlertDialog.Builder(this).setTitle("Exportar actividad")
            .setItems(arrayOf("Guardar en el teléfono", "Enviar por e-mail o WhatsApp")) { _, which -> exportKml(records, filename, which == 1) }.show()
    }

    private fun exportKml(records: List<ActivityRecord>, filename: String, share: Boolean) {
        val body = records.joinToString("\n") { record ->
            val coordinates = record.route.joinToString(" ") { "${it.longitude},${it.latitude},0" }
            "<Placemark><name>FreeRUN ${record.start}</name><LineString><tessellate>1</tessellate><coordinates>$coordinates</coordinates></LineString></Placemark>"
        }
        val kml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>$body</Document></kml>"
        try {
            if (share) {
                val file = java.io.File(cacheDir, filename)
                file.writeText(kml)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/vnd.google-earth.kml+xml"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Enviar KML"))
                return
            }
            val values = android.content.ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, filename); put(MediaStore.Downloads.MIME_TYPE, "application/vnd.google-earth.kml+xml"); put(MediaStore.Downloads.IS_PENDING, 1) }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(kml.toByteArray()) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
                Toast.makeText(this, "KML guardado en Descargas", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { Toast.makeText(this, "No se pudo exportar KML", Toast.LENGTH_LONG).show() }
    }

    private fun showSettings() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 64, 28, 0); setBackgroundColor(android.graphics.Color.rgb(15, 35, 65)) }
        root.addView(TextView(this).apply { text = "Configuración"; textSize = 30f; setTypeface(null, android.graphics.Typeface.BOLD) })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(settingTile("km / mi", "Unidades") { store.setUnit(if (store.unit() == "métrico") "imperial" else "métrico"); showSettings() })
        row1.addView(settingTile("▣", "Exportar") { Toast.makeText(this, "Exportación próximamente", Toast.LENGTH_SHORT).show() }); grid.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(settingTile("🔔", "Avisos") { Toast.makeText(this, "Avisos próximamente", Toast.LENGTH_SHORT).show() })
        row2.addView(settingTile("☁", "Privacidad") { Toast.makeText(this, "Datos guardados localmente", Toast.LENGTH_SHORT).show() }); grid.addView(row2)
        root.addView(grid); root.addView(LinearLayout(this), LinearLayout.LayoutParams(1, 0, 1f)); root.addView(navBar()); protectBottomInset(root)
        setContentView(root)
    }

    private fun installButton(label: String, packageName: String) = Button(this).apply {
        text = label
        setOnClickListener {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            try { startActivity(market) } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        }
    }

    private fun settingTile(icon: String, label: String, action: () -> Unit) = TextView(this).apply {
        text = "$icon\n$label"; textSize = 20f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(15, 35, 65)); background = GradientDrawable().apply { cornerRadius = 24f; setColor(android.graphics.Color.WHITE) }; elevation = 5f; setPadding(12, 18, 12, 18); setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(0, 220, 1f).apply { setMargins(6, 12, 6, 12) }
    }

    private fun showRecordDetail(record: ActivityRecord) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 16); setBackgroundColor(android.graphics.Color.rgb(15, 35, 65)) }
        root.addView(Button(this).apply { text = "‹  Volver al historial"; gravity = Gravity.START; setOnClickListener { showHistory() } })
        root.addView(TextView(this).apply { text = "Detalle de actividad"; textSize = 30f; setTypeface(null, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text = DateFormat.getDateTimeInstance().format(Date(record.start)); textSize = 16f })
        root.addView(Button(this).apply { text = "Descargar esta actividad en KML"; setOnClickListener { chooseExport(listOf(record), "freerun_${record.start}.kml") } })
        root.addView(TextView(this).apply { text = "Duración  ${formatDuration(record.durationMs)}\nDistancia  ${"%.2f".format(record.routeDistanceKm())} km"; textSize = 22f; setPadding(0, 20, 0, 20) })
        val detailMap = MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val line = Polyline().apply { color = android.graphics.Color.rgb(25, 72, 125); width = 9f; setPoints(record.route.map { GeoPoint(it.latitude, it.longitude) }) }
        detailMap.overlays.add(line); record.route.lastOrNull()?.let { detailMap.controller.setCenter(GeoPoint(it.latitude, it.longitude)); detailMap.controller.setZoom(16.0) }; root.addView(detailMap); root.addView(navBar()); protectBottomInset(root); setContentView(root)
    }

    private fun formatDuration(ms: Long) = String.format("%02d:%02d:%02d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60)

    private fun showUsage() {
        usageMode = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 64, 28, 0); setBackgroundColor(android.graphics.Color.rgb(15, 35, 65)) }
        val title = TextView(this).apply { text = "Actividad"; textSize = 28f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(15, 35, 65)) }
        status = TextView(this).apply { text = "Estado: detenido"; textSize = 18f }
        bpm = TextView(this).apply { text = ""; textSize = 1f }
        messageIndicator = TextView(this).apply { text = ""; textSize = 1f }
        chart = HeartRateChartView(this).apply { layoutParams = LinearLayout.LayoutParams(1, 1) }
        root.addView(title); root.addView(status)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 14, 0, 14) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val time = metricTile("00:00:00", "Duración"); val distance = metricTile("0.00 km", "Distancia")
        row1.addView(time); row1.addView(distance); stats.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val speed = metricTile("-- km/h", "Velocidad actual"); val averageSpeed = metricTile("-- km/h", "Velocidad promedio")
        row2.addView(speed); row2.addView(averageSpeed); stats.addView(row2)
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lastKm = metricTile("-- km/h", "Promedio último km"); val calories = metricTile("--", "Calorías")
        row3.addView(lastKm); row3.addView(calories); stats.addView(row3); stats.layoutParams = LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.30f).toInt()); root.addView(stats)
        Configuration.getInstance().userAgentValue = packageName
        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            controller.setZoom(16.0)
        }
        val mapContainer = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = 170 } }
        mapContainer.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 16); gravity = Gravity.CENTER; layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM) }
        val pause = activityButton("Pausa", android.graphics.Color.rgb(100, 55, 155)) { status.text = "Actividad pausada" }
        pause.layoutParams = LinearLayout.LayoutParams(0, 114, 1f).apply { setMargins(0, 0, 6, 0) }
        val stop = activityButton("Parar", android.graphics.Color.rgb(235, 45, 55)) { finishActivityPrompt() }
        stop.layoutParams = LinearLayout.LayoutParams(0, 114, 1f).apply { setMargins(6, 0, 0, 0) }
        controls.addView(pause); controls.addView(stop); mapContainer.addView(controls)
        root.addView(mapContainer)
        root.addView(bpm)
        root.addView(navBar())
        protectBottomInset(root)
        uiHandler.post(object : Runnable { override fun run() { if (activityStartedAt > 0) { val elapsed = System.currentTimeMillis() - activityStartedAt; val hours = elapsed / 3600000.0; val average = if (hours > 0) (distanceMeters / 1000.0) / hours else 0.0; time.text = String.format("%02d:%02d:%02d\nDuración", elapsed / 3600000, (elapsed / 60000) % 60, (elapsed / 1000) % 60); distance.text = String.format("%.2f km\nDistancia", distanceMeters / 1000.0); speed.text = if (lastLocation?.hasSpeed() == true) String.format("%.1f km/h\nVelocidad actual", lastLocation!!.speed * 3.6f) else "-- km/h\nVelocidad actual"; averageSpeed.text = if (average > 0) String.format("%.1f km/h\nVelocidad promedio", average) else "-- km/h\nVelocidad promedio"; lastKm.text = partialLastKmKmh(); uiHandler.postDelayed(this, 1000) } } })
        setContentView(root)
    }

    private fun statView(label: String, size: Float) = TextView(this).apply { text = label; textSize = size; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(15, 35, 65)); layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setPadding(0, 8, 0, 8) }

    private fun activityButton(label: String, color: Int, action: () -> Unit) = Button(this).apply { text = label; textSize = 18f; minHeight = 70; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.WHITE); background = GradientDrawable().apply { cornerRadius = 60f; setColor(color) }; setPadding(0, 4, 0, 4); setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(-1, 70).apply { setMargins(0, 6, 0, 6) } }

    private fun metricTile(value: String, label: String) = TextView(this).apply {
        text = "$value\n$label"; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(55, 55, 75)); setPadding(14, 10, 10, 8); gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = 26f; setColor(android.graphics.Color.rgb(247, 248, 252)) }
        layoutParams = LinearLayout.LayoutParams(0, 112, 1f).apply { setMargins(5, 4, 5, 4) }
    }

    private fun partialLastKmKmh(): String {
        val route = activeRecord?.route ?: return "-- km/h\nPromedio último km"
        if (route.size < 2) return "-- km/h\nPromedio último km"
        var distance = 0.0; var first = route.lastIndex
        while (first > 0 && distance < 1000.0) {
            val a = route[first - 1]; val b = route[first]; val result = FloatArray(1)
            android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result); distance += result[0]; first--
        }
        val duration = (route.last().time - route[first].time) / 3600000.0
        val speed = if (duration > 0) (distance / 1000.0) / duration else 0.0
        return if (speed > 0) String.format("%.1f km/h\nPromedio último km", speed) else "-- km/h\nPromedio último km"
    }

    private fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermissions()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 10)
            return
        }
        bleClient = BleHeartRateClient(this, object : BleHeartRateClient.Listener {
            override fun onStatus(message: String) { runOnUiThread { status.text = "Estado: $message" } }
            override fun onHeartRate(value: Int) {
                runOnUiThread {
                    hrmActive = true
                    bpm.text = "$value BPM"
                    chart.add(System.currentTimeMillis(), value)
                    activeRecord?.let { record -> activeRecord = record.copy(samples = record.samples + HrSample(System.currentTimeMillis(), value)) }
                    messageIndicator.text = "● Mensaje HR recibido"
                    messageIndicator.setTextColor(android.graphics.Color.rgb(46, 125, 50))
                    messageIndicator.postDelayed({ messageIndicator.text = "○ Esperando siguiente mensaje" }, 700)
                }
            }
        })
        bleClient?.start()
        return
        /* ANT+ fallback, to be used on phones/dongles supported by ANT Radio Service. */
        status.text = "Estado: buscando sensor ANT+..."
        heartRateHandle = AntPlusHeartRatePcc.requestAccess(this, this,
            object : AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc> {
                override fun onResultReceived(result: AntPlusHeartRatePcc?, requestResult: RequestAccessResult?, deviceState: DeviceState?) {
                    runOnUiThread {
                        if (requestResult == RequestAccessResult.SUCCESS && result != null) {
                            status.text = "Estado: sensor encontrado (${result.deviceName})"
                            result.subscribeHeartRateDataEvent(object : AntPlusHeartRatePcc.IHeartRateDataReceiver {
                                override fun onNewHeartRateData(timestamp: Long, flags: java.util.EnumSet<EventFlag>, heartRate: Int, beatCount: Long, rr: java.math.BigDecimal?, dataState: AntPlusHeartRatePcc.DataState) {
                                    runOnUiThread { bpm.text = "$heartRate BPM" }
                                }
                            })
                        } else {
                            status.text = "Estado: no se encontró sensor ($requestResult)"
                        }
                    }
                }
            }, null)
    }

    private fun stopMonitoring(message: String) {
        clearRecordingNotification()
        gpsActive = false; hrmActive = false
        goButton?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(android.graphics.Color.rgb(235, 82, 64)) }
        locationTracker?.stop(); locationTracker = null
        bleClient?.stop(); bleClient = null
        heartRateHandle?.close()
        heartRateHandle = null
        stopService(Intent(this, HeartRateService::class.java))
        status.text = message
        bpm.text = "-- BPM"
        activeRecord?.takeIf { it.end == 0L }?.let { record -> store.save(record.copy(end = System.currentTimeMillis())) }
        activeRecord = null
    }

    private fun finishActivityPrompt() {
        val record = activeRecord?.copy(end = System.currentTimeMillis()) ?: return
        clearRecordingNotification()
        gpsActive = false; hrmActive = false; locationTracker?.stop(); locationTracker = null; bleClient?.stop(); bleClient = null; heartRateHandle?.close(); heartRateHandle = null
        AlertDialog.Builder(this)
            .setTitle("Finalizar actividad")
            .setMessage("¿Quieres guardar esta actividad en el teléfono?")
            .setPositiveButton("Guardar") { _, _ -> store.save(record); activeRecord = null; showMenu() }
            .setNegativeButton("Descartar") { _, _ -> activeRecord = null; showMenu() }
            .setOnCancelListener { activeRecord = record }
            .show()
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions() = Build.VERSION.SDK_INT < 31 ||
        (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
         ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
}
