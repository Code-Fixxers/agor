package live.agor.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import live.agor.app.network.ConnectionState

@Composable
fun ConnectionIndicator(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = when (state) {
        ConnectionState.Connected -> Color(0xFF2EA05A)
        ConnectionState.Connecting, ConnectionState.Reconnecting -> Color(0xFFFFAA00)
        ConnectionState.Disconnected -> Color(0xFFD64545)
    }
    androidx.compose.foundation.layout.Box(modifier.size(8.dp).background(color, CircleShape))
}
