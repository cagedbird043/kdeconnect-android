package top.miceworld.kdeconnectplus.reconnect

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect_tp.R

/**
 * “记住的设备”上的重连按钮：等同下拉刷新，向局域网和自定义 IP 重发身份包。
 * 连上后设备会自动移到“已连接的设备”，按钮随之消失。
 */
@Composable
fun ReconnectButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var reconnecting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reconnecting) {
        if (reconnecting) {
            delay(RECONNECTING_MS)
            reconnecting = false
        }
    }
    TextButton(
        modifier = modifier,
        enabled = !reconnecting,
        onClick = {
            reconnecting = true
            BackgroundService.ForceRefreshConnections(context)
        },
    ) {
        Text(stringResource(if (reconnecting) R.string.plus_reconnecting else R.string.plus_reconnect))
    }
}

private const val RECONNECTING_MS = 3_000L
