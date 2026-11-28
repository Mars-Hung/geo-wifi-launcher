package tw.geowifi.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tw.geowifi.launcher.monitor.Permissions
import tw.geowifi.launcher.ui.AppNav
import tw.geowifi.launcher.ui.theme.ContextLaunchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = if (Permissions.canMonitor(this)) "rules" else "permissions"
        setContent {
            ContextLaunchTheme {
                AppNav(startDestination = start)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as GeoWifiApp).container.monitor.sync()
    }
}
