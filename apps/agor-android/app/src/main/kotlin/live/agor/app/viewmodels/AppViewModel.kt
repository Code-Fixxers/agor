package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.AppContainer
import live.agor.app.auth.AuthState
import live.agor.app.network.ConnectionState
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * Root view-model. Owns auth bootstrap + socket lifecycle.
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    val authState: StateFlow<AuthState> = container.authService.state
    val connectionState: StateFlow<ConnectionState> = container.socket.state
    val user = container.authService.user

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * Session id requested by an external entry point (notification tap, deep-link).
     * The UI observes this; on a non-null value it navigates to that chat and then
     * calls [consumePendingSessionId] so the same id doesn't re-route on recomposition.
     * Backed by [AppContainer] so MainActivity can push values before the VM exists.
     */
    val pendingSessionId: StateFlow<String?> = container.pendingSessionId
    val pendingHermesSessionId: StateFlow<String?> = container.pendingHermesSessionId

    fun consumePendingSessionId() {
        container.consumePendingSessionId()
    }

    fun consumePendingHermesSessionId() {
        container.consumePendingHermesSessionId()
    }

    init {
        viewModelScope.launch {
            container.authService.bootstrap()
            if (container.authService.state.value == AuthState.Authenticated) {
                container.socket.connect()
            }
        }
        viewModelScope.launch {
            container.voiceModels.ensureVadModelDownloaded()
        }
        container.socket.onAuthFailure = {
            container.authService.softLogout()
            container.socket.disconnect()
            AppLogger.log("Socket auth failed — soft logout", LogLevel.WARNING, "App")
        }
    }

    fun onLoginSuccess() {
        container.socket.connect()
    }

    fun showToast(text: String) { _toast.value = text }
    fun consumeToast() { _toast.value = null }

    fun logout() {
        container.socket.disconnect()
        container.authService.logout()
    }
}
