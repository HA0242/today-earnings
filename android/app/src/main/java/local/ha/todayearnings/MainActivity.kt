package local.ha.todayearnings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import local.ha.todayearnings.ui.AppTheme
import local.ha.todayearnings.ui.AppViewModel
import local.ha.todayearnings.ui.CalendarScreen
import local.ha.todayearnings.ui.HomeScreen
import local.ha.todayearnings.ui.SettingsScreen
import local.ha.todayearnings.ui.StatsScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /** 系统时间 / 时区 / 日期变化时立即重算，避免金额错乱。 */
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vm.refreshNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 只在前台（STARTED 以上）按刷新频率驱动时间；锁屏/切后台即停，回来立即重算。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.runTicker() }
        }

        setContent {
            AppTheme { AppRoot(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeReceiver)
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("首页", Icons.Filled.Home),
    Tab("日历", Icons.Filled.DateRange),
    Tab("统计", Icons.AutoMirrored.Filled.List),
    Tab("设置", Icons.Filled.Settings),
)

@Composable
private fun AppRoot(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    val now by vm.now.collectAsState()
    val calc = remember(data, now) { vm.calc(data, now) }
    var tab by rememberSaveable { mutableStateOf(0) }

    // 返回键：不在首页时先回首页，再按才退出
    BackHandler(enabled = tab != 0) { tab = 0 }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(
                    data = data,
                    now = now,
                    calc = calc,
                    onSwitchRole = vm::switchRole,
                    onSetStartDate = vm::setStartDate,
                )
                1 -> CalendarScreen(
                    data = data,
                    calc = calc,
                    onToggleWorkday = vm::toggleWorkday,
                    onSetLeave = vm::setLeave,
                    onSetOvertime = vm::setOvertime,
                )
                2 -> StatsScreen(calc)
                else -> SettingsScreen(vm, data, calc, onOpenCalendar = { tab = 1 })
            }
        }
    }
}
