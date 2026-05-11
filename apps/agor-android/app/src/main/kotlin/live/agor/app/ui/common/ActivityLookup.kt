package live.agor.app.ui.common

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

tailrec fun Context.findFragmentActivity(): FragmentActivity? {
    return when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
}
