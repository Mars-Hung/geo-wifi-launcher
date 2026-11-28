package tw.geowifi.launcher

import android.app.Application
import android.content.Context
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.osmdroid.config.Configuration
import tw.geowifi.launcher.data.RuleRepository
import tw.geowifi.launcher.data.db.AppDatabase
import tw.geowifi.launcher.monitor.MonitorController
import tw.geowifi.launcher.monitor.Notifications
import tw.geowifi.launcher.monitor.RuleEvaluator
import tw.geowifi.launcher.monitor.SensorHub

class GeoWifiApp : Application() {
    lateinit var container: AppContainer
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        val config = Configuration.getInstance()
        config.userAgentValue = "ContextLaunch/0.1 $packageName"
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Notifications.ensureChannels(this)
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = Room.databaseBuilder(appContext, AppDatabase::class.java, "context-launch.db").build()
    val repository = RuleRepository(database)
    val hub = SensorHub()
    val monitor = MonitorController(appContext, repository)
    val evaluator = RuleEvaluator(
        context = appContext,
        repository = repository,
        hub = hub,
        ensureRunning = { monitor.ensureRunning() },
        reschedule = { monitor.scheduleOnly() },
    )
}
