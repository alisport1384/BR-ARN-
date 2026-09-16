package com.bigrocket.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile
import com.bigrocket.service.EmbeddedAetherRuntime
import com.bigrocket.service.DynamicWeightCalculator
import com.bigrocket.service.NetworkPreferenceStore
import com.bigrocket.service.AppLogger
import com.bigrocket.R
import com.bigrocket.service.BigRocketVpnService
import com.bigrocket.service.BondingMode
import com.bigrocket.service.BondingStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The three modes the app can run in - exactly one embedded upstream is ever active. */
enum class UpstreamChoice { NONE, AETHER }

class MainActivity : AppCompatActivity() {

    private lateinit var tvWifiStatus: TextView
    private lateinit var tvCellularStatus: TextView
    private lateinit var tvPath3Status: TextView
    private lateinit var spinnerWifiScore: Spinner
    private lateinit var spinnerCellularScore: Spinner
    private lateinit var tvAiStatus: TextView
    private lateinit var tvAiLog: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvVpnStatus: TextView
    private lateinit var tvBarLegend: TextView
    private lateinit var barWifi: View
    private lateinit var barCellular: View
    private lateinit var chartThroughput: SimpleLineChartView
    private lateinit var btnToggleVpn: Button
    private lateinit var tvAetherMode: TextView
    private lateinit var spinnerAetherMode: Spinner
    private lateinit var aetherEmbeddedPanel: ComposeView
    private lateinit var toolbar: Toolbar
    private var upstreamChoice = UpstreamChoice.NONE
    // Backed by Compose state (not a plain var): AetherEmbeddedPanel reads this inside
    // aetherEmbeddedPanel.setContent{}, so a plain var mutation here was invisible to Compose -
    // onProfileChange updated the value but nothing recomposed, making every Advanced-panel
    // control (protocol, scan mode, etc.) look locked/unresponsive until some unrelated state
    // (e.g. EmbeddedAetherRuntime.enabled) happened to force a recompose.
    private var aetherProfile by mutableStateOf(ConnectionProfile())
    private lateinit var aetherProfileStore: ProfileStore

    private var isVpnRunning = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            tvVpnStatus.text = "وضعیت اتصال: اجازه داده نشد"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Whether granted or not, we still proceed - the VPN/relay itself works either way,
        // only the persistent status notification would stay hidden if denied.
        proceedToVpnPermissionCheck()
    }

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult // user cancelled the picker
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(AppLogger.exportText().toByteArray())
            }
            Toast.makeText(this, "لاگ ذخیره شد", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ذخیره لاگ ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLogger.init(this)
        initViews()
        setSupportActionBar(toolbar)
        setupUpstreamIntegration()

        btnToggleVpn.setOnClickListener {
            if (isVpnRunning) {
                stopVpnService()
            } else {
                prepareAndStart()
            }
        }

        observeBondingStatus()
        requestBatteryOptimizationExemption()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvCellularStatus = findViewById(R.id.tvCellularStatus)
        tvPath3Status = findViewById(R.id.tvPath3Status)
        spinnerWifiScore = findViewById(R.id.spinnerWifiScore)
        spinnerCellularScore = findViewById(R.id.spinnerCellularScore)
        setupNetworkScoreControls()
        tvAiStatus = findViewById(R.id.tvAiStatus)
        tvAiLog = findViewById(R.id.tvAiLog)
        tvMode = findViewById(R.id.tvMode)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        tvBarLegend = findViewById(R.id.tvBarLegend)
        barWifi = findViewById(R.id.barWifi)
        barCellular = findViewById(R.id.barCellular)
        chartThroughput = findViewById(R.id.chartThroughput)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        tvAetherMode = findViewById(R.id.tvAetherMode)
        spinnerAetherMode = findViewById(R.id.spinnerAetherMode)
        aetherEmbeddedPanel = findViewById(R.id.aetherEmbeddedPanel)
    }

    private fun setupNetworkScoreControls() {
        val values = arrayOf("1", "2", "3", "4", "5")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)

        spinnerWifiScore.adapter = adapter
        spinnerCellularScore.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values
        )

        spinnerWifiScore.setSelection(NetworkPreferenceStore.wifiScore(this) - 1, false)
        spinnerCellularScore.setSelection(NetworkPreferenceStore.cellularScore(this) - 1, false)

        spinnerWifiScore.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                NetworkPreferenceStore.setWifiScore(this@MainActivity, position + 1)
                DynamicWeightCalculator.configureUserScores(
                    wifiScore = position + 1,
                    cellularScore = NetworkPreferenceStore.cellularScore(this@MainActivity)
                )
            }
        }

        spinnerCellularScore.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                NetworkPreferenceStore.setCellularScore(this@MainActivity, position + 1)
                DynamicWeightCalculator.configureUserScores(
                    wifiScore = NetworkPreferenceStore.wifiScore(this@MainActivity),
                    cellularScore = position + 1
                )
            }
        }

        DynamicWeightCalculator.configureUserScores(
            wifiScore = NetworkPreferenceStore.wifiScore(this),
            cellularScore = NetworkPreferenceStore.cellularScore(this)
        )
    }

    private fun setupUpstreamIntegration() {
        aetherProfileStore = ProfileStore(applicationContext)
        aetherEmbeddedPanel.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        aetherEmbeddedPanel.setContent {
            val aetherRuntimeEnabled = EmbeddedAetherRuntime.enabled.collectAsState().value
            // Was a bare MaterialTheme{} (Android's default M3 baseline colors, e.g.
            // stock purple) - completely disconnected both from BigRocket's own
            // window theme (Theme.AetherMobile / colors.xml: aether_background
            // #0A0E1A, aether_primary #4C8DFF, already the Aether navy palette) and
            // from Aether's own screens (HomeScreen etc., which use AetherTheme).
            // That's exactly why this embedded panel visually clashed with the rest
            // of the app. AetherTheme is the same theme Aether's own UI uses, so this
            // panel now matches it - and therefore matches BigRocket, since BigRocket
            // was already themed to Aether's own palette.
            studio.cluvex.aether.ui.theme.AetherTheme {
                Surface {
                    AetherEmbeddedPanel(
                        profile = aetherProfile,
                        onProfileChange = { profile ->
                            aetherProfile = profile
                            lifecycleScope.launch { aetherProfileStore.save(profile) }
                        },
                        enabled = aetherRuntimeEnabled,
                        onEnabledChange = { selected -> setUpstreamChoice(if (selected) UpstreamChoice.AETHER else UpstreamChoice.NONE) },
                    )
                }
            }
        }

        lifecycleScope.launch {
            aetherProfileStore.profile.collectLatest { aetherProfile = it.copy(proxyMode = true) }
        }



        val options = arrayOf("فقط BigRocket", "BigRocket + Aether")
        spinnerAetherMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinnerAetherMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = when (position) {
                    1 -> UpstreamChoice.AETHER
                    else -> UpstreamChoice.NONE
                }
                setUpstreamChoice(selected)
            }
        }
        renderUpstreamChoice()
    }

    /**
     * Single place that switches the active upstream. Always stops whichever runtime is
     * NOT the new choice first - a stray "both active at once" state would race both
     * SOCKS upstreams for BigRocketVpnService.setUpstreamMode.
     */
    private fun setUpstreamChoice(choice: UpstreamChoice) {
        if (upstreamChoice == choice) return
        AppLogger.log("Upstream", "switching $upstreamChoice -> $choice")
        upstreamChoice = choice
        renderUpstreamChoice()

        if (choice != UpstreamChoice.AETHER) EmbeddedAetherRuntime.stop(this)

        val active = BondingStatus.state.value.isServiceActive
        if (!active) return
        when (choice) {
            UpstreamChoice.AETHER -> EmbeddedAetherRuntime.start(this, aetherProfile)
            UpstreamChoice.NONE -> Unit
        }
    }

    private fun renderUpstreamChoice() {
        tvAetherMode.text = when (upstreamChoice) {
            UpstreamChoice.AETHER -> "مسیر بعد از Bonding: BigRocket → Aether"
            UpstreamChoice.NONE -> "مسیر بعد از Bonding: فقط BigRocket"
        }
        // Aether's settings panel only makes sense once BigRocket + Aether is actually
        // selected - showing it unconditionally exposed knobs (protocol, scan mode, ...)
        // for an engine that wasn't even the active upstream.
        aetherEmbeddedPanel.visibility = if (upstreamChoice == UpstreamChoice.AETHER) View.VISIBLE else View.GONE
    }


    private fun observeBondingStatus() {
        lifecycleScope.launch {
            BondingStatus.state.collectLatest { state ->
                val wifiText = if (state.isWifiConnected) {
                    "Wi-Fi: متصل (${state.wifiLatencyMs}ms) - سهم: ${state.wifiWeight}%"
                } else {
                    "Wi-Fi: قطع است"
                }
                tvWifiStatus.text = wifiText

                val cellularText = if (state.isCellularConnected) {
                    "Cellular: متصل (${state.cellularLatencyMs}ms) - سهم: ${state.cellularWeight}%"
                } else {
                    "Cellular: قطع است"
                }
                tvCellularStatus.text = cellularText

                tvPath3Status.text = if (state.path3Connected) {
                    "مسیر ۳: متصل (${state.path3LatencyMs}ms)"
                } else {
                    "مسیر ۳: قطع"
                }

                tvAiStatus.text = "وضعیت AI: ${state.mode.name}"
                tvAiLog.text = when (state.mode) {
                    BondingMode.BONDING_ACTIVE ->
                        "ترکیب فعال: Wi-Fi (${state.wifiWeight}%) | Cellular (${state.cellularWeight}%)"
                    BondingMode.SINGLE_PATH ->
                        if (state.isWifiConnected) "مسیر منفرد: استفاده اختصاصی از Wi-Fi"
                        else "مسیر منفرد: استفاده اختصاصی از Cellular"
                    BondingMode.IDLE -> "عدم دسترسی به اینترنت"
                }

                tvMode.text = if (state.mode == BondingMode.BONDING_ACTIVE) {
                    "حالت فعلی: Multi-Path Bonding (ترکیبی)"
                } else {
                    "حالت فعلی: Single-Path (تک‌مسیره)"
                }

                // One decimal place, not rounded to a whole number (e.g. "5.1", "0.5",
                // "100.6") - a plain roundToInt() still collapsed most real-world
                // sub-1 Mbps readings down to a flat "0".
                val displaySpeed = String.format(java.util.Locale.US, "%.1f", state.bondedSpeedMbps)
                tvSpeed.text = "سرعت ترکیبی: $displaySpeed Mbps"

                isVpnRunning = state.isServiceActive
                if (isVpnRunning) {
                    if (upstreamChoice == UpstreamChoice.AETHER && !EmbeddedAetherRuntime.isRunning()) {
                        EmbeddedAetherRuntime.start(this@MainActivity, aetherProfile)
                    }
                } else {
                    if (EmbeddedAetherRuntime.isRunning()) EmbeddedAetherRuntime.stop(this@MainActivity)
                }
                btnToggleVpn.text = if (isVpnRunning) "قطع اتصال BigRocket" else "شروع اتصال BigRocket"
                tvVpnStatus.text = if (isVpnRunning) "وضعیت اتصال: VPN فعال" else "وضعیت اتصال: غیرفعال"
                tvBarLegend.text = "Wi-Fi ${state.wifiWeight}% · Cellular ${state.cellularWeight}%"

                // Cheap split-bar update: just change layout weights, no custom drawing.
                (barWifi.layoutParams as LinearLayout.LayoutParams).weight =
                    state.wifiWeight.coerceAtLeast(1).toFloat()
                (barCellular.layoutParams as LinearLayout.LayoutParams).weight =
                    state.cellularWeight.coerceAtLeast(1).toFloat()
                barWifi.requestLayout()
                barCellular.requestLayout()
            }
        }

        lifecycleScope.launch {
            BondingStatus.throughputHistory.collectLatest { history ->
                chartThroughput.setData(history.map { it.toFloat() })
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Some ROMs block the direct request; no functional dependency on this dialog.
        }
    }

    private fun prepareAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToVpnPermissionCheck()
    }

    private fun proceedToVpnPermissionCheck() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        AppLogger.log("VPN", "startVpnService() called, upstreamChoice=$upstreamChoice")
        val intent = Intent(this, BigRocketVpnService::class.java)
        ContextCompat.startForegroundService(this, intent)

        isVpnRunning = true
        btnToggleVpn.text = "قطع اتصال BigRocket"
        tvVpnStatus.text = "وضعیت اتصال: VPN فعال"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Reflects AppLogger's true current state every time the menu opens, rather than
        // trusting whatever the item's checked state happened to be left at - matters after a
        // process restart, since the menu view itself doesn't survive that but the underlying
        // preference does.
        menu.findItem(R.id.action_toggle_logging)?.isChecked = AppLogger.isEnabled()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_logging -> {
                val newValue = !item.isChecked
                item.isChecked = newValue
                AppLogger.setEnabled(this, newValue)
                Toast.makeText(
                    this,
                    if (newValue) "ثبت لاگ فعال شد" else "ثبت لاگ غیرفعال شد",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
            R.id.action_save_log -> {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                saveLogLauncher.launch("bigrocket_log_$timestamp.txt")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun stopVpnService() {
        AppLogger.log("VPN", "stopVpnService() called")
        EmbeddedAetherRuntime.stop(this)
        val intent = Intent(this, BigRocketVpnService::class.java).apply {
            action = BigRocketVpnService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, intent)

        isVpnRunning = false
        btnToggleVpn.text = "شروع اتصال BigRocket"
        tvVpnStatus.text = "وضعیت اتصال: غیرفعال"
    }
}
