package ru.open60m.quvpn.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.open60m.quvpn.R
import ru.open60m.quvpn.databinding.ActivityMainBinding
import ru.open60m.quvpn.model.VpnState
import ru.open60m.quvpn.service.QuVpnService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            vm.connect()
        } else {
            Toast.makeText(this, "Разрешение VPN отклонено", Toast.LENGTH_SHORT).show()
            vm.onServiceError("Разрешение VPN отклонено")
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(QuVpnService.EXTRA_STATE)) {
                QuVpnService.STATE_CONNECTED    -> vm.onServiceConnected()
                QuVpnService.STATE_DISCONNECTED -> vm.onServiceDisconnected()
                QuVpnService.STATE_TIMEOUT -> {
                    Toast.makeText(this@MainActivity, "Бесплатные 30 минут закончились", Toast.LENGTH_LONG).show()
                    vm.onFreeTimeExpired()
                }
                QuVpnService.STATE_ERROR -> {
                    val msg = intent.getStringExtra(QuVpnService.EXTRA_ERROR_MSG) ?: "Ошибка"
                    vm.onServiceError(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm.restoreState()
        setupServerList()
        setupButtons()
        observeState()
        registerServiceReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
    }

    private fun setupServerList() {
        val serverViews = listOf(binding.serverDe, binding.serverNl, binding.serverRu)

        vm.servers.forEachIndexed { i, server ->
            serverViews[i].apply {
                ivFlag.setImageResource(server.flagRes)
                tvServerName.text = server.name
                tvServerCity.text = server.city
                tvPremiumTag.visibility = if (server.premiumOnly) View.VISIBLE else View.GONE

                root.setOnClickListener {
                    if (server.premiumOnly && !vm.premium.value) {
                        Toast.makeText(root.context, "🔒 Только для Premium", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    vm.selectServer(server.key)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.selectedServer.collect { key ->
                    serverViews.forEachIndexed { i, v ->
                        v.root.isSelected = vm.servers[i].key == key
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnToggle.setOnClickListener {
            when (vm.vpnState.value) {
                is VpnState.Off, is VpnState.Error -> requestVpnAndConnect()
                is VpnState.Connected -> vm.disconnect().also { stopVpnService() }
                else -> {}
            }
        }

        binding.btnChangeNode.setOnClickListener { vm.changeNode() }

        binding.btnPremium.setOnClickListener {
            openUrl("https://t.me/BelkaNet_bot?start=premium-app")
        }

        binding.btnSupport.setOnClickListener {
            openUrl("https://t.me/OPEN60_S?text=Помощь Qu VPN❤️")
        }

        binding.btnDevices.setOnClickListener {
            openUrl("https://t.me/BelkaNet_bot?start=ext-quattro")
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun requestVpnAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            vm.connect()
        }
    }

    private fun startVpnService(state: VpnState.Connected) {
        val data = vm.getSessionData() ?: return
        val (host, port, token) = data
        val serverKey = state.serverKey
        val serverName = vm.servers.find { it.key == serverKey }?.name ?: "Qu VPN"

        val serviceIntent = Intent(this, QuVpnService::class.java).apply {
            action = QuVpnService.ACTION_CONNECT
            putExtra(QuVpnService.EXTRA_PROXY_HOST, host)
            putExtra(QuVpnService.EXTRA_PROXY_PORT, port)
            putExtra(QuVpnService.EXTRA_SERVER_NAME, serverName)
            putExtra(QuVpnService.EXTRA_ACCESS_TOKEN, token)
            putExtra(QuVpnService.EXTRA_PREMIUM, state.premium)
            putExtra(
                QuVpnService.EXTRA_DEADLINE,
                if (state.premium) 0L else state.startedAt + MainViewModel.FREE_LIMIT_MS
            )
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopVpnService() {
        startService(Intent(this, QuVpnService::class.java).apply {
            action = QuVpnService.ACTION_DISCONNECT
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.vpnState.collect { state ->
                        updateUI(state)
                        // Новая сессия (подключение или смена сервера) — (пере)запускаем туннель
                        if (state is VpnState.Connected && state.revision != vm.appliedRevision) {
                            vm.appliedRevision = state.revision
                            startVpnService(state)
                        }
                    }
                }
                launch {
                    vm.switching.collect { busy ->
                        binding.btnChangeNode.isEnabled = !busy
                        binding.btnChangeNode.alpha = if (busy) 0.5f else 1f
                    }
                }
                launch {
                    vm.nodeMessage.collect { msg ->
                        binding.tvNodeMsg.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
                        binding.tvNodeMsg.text = msg ?: ""
                    }
                }
                launch {
                    while (true) {
                        tickFreeTimer()
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun freeSubText(state: VpnState.Connected): String {
        val left = (state.startedAt + MainViewModel.FREE_LIMIT_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        return String.format(
            Locale.US, "Бесплатный режим · осталось %02d:%02d",
            left / 60_000, (left / 1000) % 60
        )
    }

    private fun tickFreeTimer() {
        val s = vm.vpnState.value
        if (s is VpnState.Connected && !s.premium) binding.tvSub.text = freeSubText(s)
    }

    private fun updateUI(state: VpnState) {
        with(binding) {
            btnChangeNode.visibility = if (state is VpnState.Connected) View.VISIBLE else View.GONE
            when (state) {
                is VpnState.Off -> {
                    dot.setBackgroundResource(R.drawable.dot_off)
                    tvStatus.text = "Отключено"
                    tvStatus.setTextColor(getColor(R.color.status_off))
                    btnToggle.text = "Подключиться"
                    btnToggle.isEnabled = true
                    btnToggle.setBackgroundResource(R.drawable.btn_toggle)
                    tvSub.text = "Вы не защищены"
                    premiumBox.visibility = View.VISIBLE
                }
                is VpnState.Connecting -> {
                    dot.setBackgroundResource(R.drawable.dot_loading)
                    tvStatus.text = "Подключение…"
                    tvStatus.setTextColor(getColor(R.color.status_loading))
                    btnToggle.text = "Подключение…"
                    btnToggle.isEnabled = false
                    btnToggle.setBackgroundResource(R.drawable.btn_toggle_connecting)
                    tvSub.text = "Устанавливаем соединение"
                }
                is VpnState.Connected -> {
                    val name = vm.servers.find { it.key == state.serverKey }?.name ?: state.serverKey
                    dot.setBackgroundResource(R.drawable.dot_on)
                    tvStatus.text = "Подключён к $name"
                    tvStatus.setTextColor(getColor(R.color.status_on))
                    btnToggle.text = "Отключиться"
                    btnToggle.isEnabled = true
                    btnToggle.setBackgroundResource(R.drawable.btn_toggle_on)
                    tvSub.text = if (state.premium) "Premium активен" else freeSubText(state)
                    premiumBox.visibility = if (state.premium) View.GONE else View.VISIBLE
                }
                is VpnState.Disconnecting -> {
                    dot.setBackgroundResource(R.drawable.dot_loading)
                    tvStatus.text = "Отключение…"
                    tvStatus.setTextColor(getColor(R.color.status_loading))
                    btnToggle.text = "Отключение…"
                    btnToggle.isEnabled = false
                    btnToggle.setBackgroundResource(R.drawable.btn_toggle_connecting)
                    tvSub.text = "Завершаем сессию"
                }
                is VpnState.Error -> {
                    dot.setBackgroundResource(R.drawable.dot_off)
                    tvStatus.text = "Ошибка"
                    tvStatus.setTextColor(getColor(R.color.status_error))
                    btnToggle.text = "Повторить"
                    btnToggle.isEnabled = true
                    btnToggle.setBackgroundResource(R.drawable.btn_toggle)
                    tvSub.text = state.message
                    premiumBox.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun registerServiceReceiver() {
        val filter = IntentFilter(QuVpnService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }
}
