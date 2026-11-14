package ru.open60m.quvpn.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.launch
import ru.open60m.quvpn.R
import ru.open60m.quvpn.databinding.ActivityMainBinding
import ru.open60m.quvpn.model.VpnState
import ru.open60m.quvpn.service.QuVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    // Запрос разрешения VPN
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Разрешение VPN отклонено", Toast.LENGTH_SHORT).show()
            vm.onServiceError("Разрешение VPN отклонено")
        }
    }

    // Receiver от VpnService
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(QuVpnService.EXTRA_STATE)) {
                QuVpnService.STATE_CONNECTED    -> vm.onServiceConnected()
                QuVpnService.STATE_DISCONNECTED -> vm.onServiceDisconnected()
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
        setupToggleButton()
        observeState()
        registerServiceReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
    }

    // ======== Серверы ========
    private fun setupServerList() {
        val serverViews = listOf(
            binding.serverDe,
            binding.serverNl,
            binding.serverRu
        )

        vm.servers.forEachIndexed { i, server ->
            serverViews[i].apply {
                tvServerName.text  = server.name
                tvServerFlag.text  = server.flag
                tvPremiumTag.visibility = if (server.premiumOnly) View.VISIBLE else View.GONE

                setOnClickListener {
                    if (server.premiumOnly && !vm.premium.value) {
                        Toast.makeText(context, "🔒 Только для Premium", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    vm.selectServer(server.key)
                }
            }
        }

        // Следим за выбранным сервером
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

    // ======== Кнопка ========
    private fun setupToggleButton() {
        binding.btnToggle.setOnClickListener {
            when (vm.vpnState.value) {
                is VpnState.Off, is VpnState.Error -> requestVpnAndConnect()
                is VpnState.Connected -> vm.disconnect().also { stopVpnService() }
                else -> { /* переходное состояние — игнорируем */ }
            }
        }
    }

    private fun requestVpnAndConnect() {
        vm.connect()   // ViewModel запускает API-запрос
        // Когда ViewModel перейдёт в Connected — мы запустим сервис (см. observeState)
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    // ======== VPN Service ========
    private fun startVpnService() {
        val data = vm.getSessionData() ?: return
        val (host, port, token) = data
        val serverKey = vm.selectedServer.value
        val serverName = vm.servers.find { it.key == serverKey }?.name ?: "Qu VPN"

        val serviceIntent = Intent(this, QuVpnService::class.java).apply {
            action = QuVpnService.ACTION_CONNECT
            putExtra(QuVpnService.EXTRA_PROXY_HOST, host)
            putExtra(QuVpnService.EXTRA_PROXY_PORT, port)
            putExtra(QuVpnService.EXTRA_SERVER_NAME, serverName)
            putExtra(QuVpnService.EXTRA_ACCESS_TOKEN, token)
            putExtra(QuVpnService.EXTRA_PREMIUM, vm.premium.value)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopVpnService() {
        startService(Intent(this, QuVpnService::class.java).apply {
            action = QuVpnService.ACTION_DISCONNECT
        })
    }

    // ======== Наблюдение за состоянием ========
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.vpnState.collect { state ->
                    updateUI(state)
                    // Когда сессия готова — запускаем сервис
                    if (state is VpnState.Connected) {
                        startVpnService()
                    }
                }
            }
        }
    }

    private fun updateUI(state: VpnState) {
        with(binding) {
            when (state) {
                is VpnState.Off -> {
                    dot.setBackgroundResource(R.drawable.dot_off)
                    tvStatus.text = "Отключено"
                    tvStatus.setTextColor(getColor(R.color.status_off))
                    btnToggle.text = "Подключиться"
                    btnToggle.isEnabled = true
                    tvSub.text = "Вы не защищены"
                }
                is VpnState.Connecting -> {
                    dot.setBackgroundResource(R.drawable.dot_loading)
                    tvStatus.text = "Подключение…"
                    tvStatus.setTextColor(getColor(R.color.status_loading))
                    btnToggle.text = "Подключение…"
                    btnToggle.isEnabled = false
                    tvSub.text = "Устанавливаем соединение"
                }
                is VpnState.Connected -> {
                    val name = vm.servers.find { it.key == state.serverKey }?.name ?: state.serverKey
                    dot.setBackgroundResource(R.drawable.dot_on)
                    tvStatus.text = "Подключён к $name"
                    tvStatus.setTextColor(getColor(R.color.status_on))
                    btnToggle.text = "Отключиться"
                    btnToggle.isEnabled = true
                    tvSub.text = if (state.premium) "Premium активен" else "Бесплатный режим"
                }
                is VpnState.Disconnecting -> {
                    dot.setBackgroundResource(R.drawable.dot_loading)
                    tvStatus.text = "Отключение…"
                    tvStatus.setTextColor(getColor(R.color.status_loading))
                    btnToggle.text = "Отключение…"
                    btnToggle.isEnabled = false
                    tvSub.text = "Завершаем сессию"
                }
                is VpnState.Error -> {
                    dot.setBackgroundResource(R.drawable.dot_off)
                    tvStatus.text = "Ошибка"
                    tvStatus.setTextColor(getColor(R.color.status_error))
                    btnToggle.text = "Повторить"
                    btnToggle.isEnabled = true
                    tvSub.text = state.message
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
