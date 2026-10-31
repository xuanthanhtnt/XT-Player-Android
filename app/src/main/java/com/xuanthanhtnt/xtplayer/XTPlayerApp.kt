package com.xuanthanhtnt.xtplayer

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.xuanthanhtnt.xtplayer.core.AppSettings
import com.xuanthanhtnt.xtplayer.core.SecureStore
import com.xuanthanhtnt.xtplayer.data.AppDatabase
import com.xuanthanhtnt.xtplayer.data.LibraryDao
import com.xuanthanhtnt.xtplayer.metadata.LibraryScanner
import com.xuanthanhtnt.xtplayer.network.ServerRegistry
import com.xuanthanhtnt.xtplayer.trakt.TraktService
import com.xuanthanhtnt.xtplayer.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XTPlayerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.xuanthanhtnt.xtplayer.core.CrashLog.install(this)
        AppGraph.init(this)
    }

    // Posters and backdrops are re-used constantly while browsing the library.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(512L shl 20).build() }
        .crossfade(true)
        .build()
}

/** App-wide singletons (the Android counterpart of the SwiftUI environment objects). */
object AppGraph {
    lateinit var context: Context
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var secrets: SecureStore
        private set

    val dao: LibraryDao get() = db.dao()

    /** Work that outlives a screen: scans, artwork, Trakt sync. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val scanner by lazy { LibraryScanner() }
    val trakt by lazy { TraktService() }
    val updates by lazy { UpdateChecker() }

    private fun watchNetwork(app: Application) {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private var current: android.net.Network? = null

                override fun onAvailable(network: android.net.Network) {
                    if (current != null && current != network) com.xuanthanhtnt.xtplayer.network.SmbConnections.resetAll()
                    current = network
                }

                override fun onLost(network: android.net.Network) {
                    if (network == current) {
                        current = null
                        com.xuanthanhtnt.xtplayer.network.SmbConnections.resetAll()
                    }
                }
            })
        }
    }

    fun init(app: Application) {
        context = app
        settings = AppSettings(app)
        secrets = SecureStore(app)
        db = AppDatabase.open(app)
        watchNetwork(app)
        scope.launch(Dispatchers.IO) {
            ServerRegistry.preload()
            trakt.sync()
            scanner.generateMissingArtwork()
            while (true) {
                kotlinx.coroutines.delay(900_000)
                if (!com.xuanthanhtnt.xtplayer.player.PlayerLauncher.isOpen) trakt.syncIfStale(600)
            }
        }
        // Watched elsewhere (Infuse, Apple TV, the Mac) while this app sat in the background.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                scope.launch { trakt.syncIfStale(300) }
            }
        })
    }
}
