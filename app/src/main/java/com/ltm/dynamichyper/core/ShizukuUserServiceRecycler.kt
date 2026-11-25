package com.ltm.dynamichyper.core

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.ltm.dynamichyper.BuildConfig
import com.ltm.dynamichyper.IPrivilegedLogCallback
import com.ltm.dynamichyper.IPrivilegedService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuUserServiceRecycler {

    private val serviceMutex = Mutex()
    private var cachedService: IPrivilegedService? = null
    private var serviceConnection: ServiceConnection? = null
    private var lastPingAttempt = 0L
    private const val TAG = "ShizukuRecycler"
    private const val PING_INTERVAL_MS = 5000L
    private const val BIND_TIMEOUT_MS = 10000L

    @Volatile private var logCallbackEnabled = false
    private val logCallback = object : IPrivilegedLogCallback.Stub() {
        override fun log(level: Int, tag: String?, message: String?) {
            val safeTag = tag ?: "PrivilegedService"
            val safeMsg = message ?: ""
            when (level) {
                0 -> Log.d(safeTag, safeMsg)
                1 -> Log.i(safeTag, safeMsg)
                2 -> Log.w(safeTag, safeMsg)
                3 -> Log.e(safeTag, safeMsg)
                else -> Log.d(safeTag, safeMsg)
            }
        }
    }

    suspend fun getPrivilegedService(): IPrivilegedService {
        serviceMutex.withLock {
            cachedService?.let { cached ->
                val now = System.currentTimeMillis()
                if (now - lastPingAttempt > PING_INTERVAL_MS) {
                    try {
                        if (cached.asBinder().pingBinder()) {
                            lastPingAttempt = now
                            return cached
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Service ping failed, rebinding: ${e.message}")
                    }
                    lastPingAttempt = now
                    cachedService = null
                } else {
                    return cached
                }
            }

            Log.d(TAG, "Establishing new service connection...")
            return establishServiceConnection().also {
                lastPingAttempt = System.currentTimeMillis()
                Log.d(TAG, "Service connection established successfully")
            }
        }
    }

    private suspend fun establishServiceConnection(): IPrivilegedService {
        return withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        Log.d(TAG, "onServiceConnected called with service=$service")
                        if (service != null) {
                            val privileged = IPrivilegedService.Stub.asInterface(service)
                            if (logCallbackEnabled) {
                                try {
                                    privileged.setLogCallback(logCallback)
                                } catch (_: Exception) {}
                            }
                            cachedService = privileged
                            serviceConnection = this
                            continuation.resume(privileged)
                        } else {
                            continuation.resumeWithException(Exception("Shizuku UserService returned null binder"))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        Log.w(TAG, "Service disconnected unexpectedly")
                        cachedService = null
                    }
                }

                val args = Shizuku.UserServiceArgs(
                    ComponentName(BuildConfig.APPLICATION_ID, PrivilegedServiceImpl::class.java.name)
                )
                    .daemon(true)
                    .processNameSuffix("privileged")
                    .debuggable(BuildConfig.DEBUG)
                    .version(1)

                try {
                    Log.d(TAG, "Calling Shizuku.bindUserService()...")
                    Shizuku.bindUserService(args, connection)
                } catch (e: Exception) {
                    Log.e(TAG, "bindUserService threw exception: ${e.message}", e)
                    continuation.resumeWithException(e)
                }
            }
        } ?: throw Exception("Timeout binding Shizuku UserService")
    }
}
