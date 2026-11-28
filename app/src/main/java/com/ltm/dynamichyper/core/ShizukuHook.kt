package com.ltm.dynamichyper.core

import android.content.Context
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Móc nối trực tiếp vào Binder hệ thống (IConnectivityManager / INetworkManagementService /
 * INetworkPolicyManager / IActivityManager) thông qua ShizukuBinderWrapper để:
 * 1. Khóa tường lửa đa chuỗi (`OEM_DENY_CHAIN = 9`, `FIREWALL_CHAIN_STANDBY = 3`)
 * 2. Ghi chính sách cấm mạng nền bền vững xuống `/data/system/netpolicy.xml` qua `INetworkPolicyManager`
 * 3. Buộc dừng (`forceStopPackage`) tiến trình `com.xiaomi.xmsf` để đóng mọi kết nối TCP đang mở,
 *    đảm bảo Đảo Động vẫn hoạt động 24/7 ngay cả khi người dùng tắt hoàn toàn Tùy chọn nhà phát triển.
 */
object ShizukuHook {
    private const val TAG = "DynamicHyper:ShizukuHook"
    private const val OEM_DENY_CHAIN = 9
    private const val STANDBY_DENY_CHAIN = 3
    private const val POLICY_NONE = 0
    private const val POLICY_REJECT_METERED_BACKGROUND = 1

    private data class ServiceBackend(
        val serviceName: String,
        val stubClassName: String,
        val label: String
    )

    private val serviceBackends = listOf(
        ServiceBackend(
            serviceName = Context.CONNECTIVITY_SERVICE,
            stubClassName = "android.net.IConnectivityManager\$Stub",
            label = "ConnectivityManager"
        ),
        ServiceBackend(
            serviceName = "network_management",
            stubClassName = "android.os.INetworkManagementService\$Stub",
            label = "NetworkManagementService"
        )
    )

    private val hookedServiceCache = ConcurrentHashMap<String, Any>()

    fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean {
        val rule = if (enabled) 0 else 2
        val chains = listOf(OEM_DENY_CHAIN, STANDBY_DENY_CHAIN)
        var anySuccess = false
        val failures = mutableListOf<String>()

        // 1. Khóa qua IConnectivityManager trên cả Chain 9 (OEM_DENY) và Chain 3 (STANDBY)
        try {
            val cm = getHookedConnectivityManager()
            for (chain in chains) {
                try {
                    if (!enabled) {
                        cm.javaClass.getMethod("setFirewallChainEnabled", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                            .invoke(cm, chain, true)
                    }
                    cm.javaClass.getMethod("setUidFirewallRule", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(cm, chain, uid, rule)
                    anySuccess = true
                    Log.d(TAG, "Firewall chain $chain ${if (enabled) "restored" else "blocked"} for uid=$uid via IConnectivityManager")
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            failures += "IConnectivityManager: ${t.message}"
        }

        // 2. Khóa qua INetworkManagementService trên cả Chain 9 và Chain 3
        try {
            val nm = getHookedNetworkManagementService()
            for (chain in chains) {
                try {
                    if (!enabled) {
                        nm.javaClass.getMethod("setFirewallChainEnabled", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                            .invoke(nm, chain, true)
                    }
                    try {
                        nm.javaClass.getMethod("setUidFirewallRule", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            .invoke(nm, chain, uid, rule)
                    } catch (_: Throwable) {
                        nm.javaClass.getMethod("setFirewallUidRule", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            .invoke(nm, chain, uid, rule)
                    }
                    anySuccess = true
                    Log.d(TAG, "Firewall chain $chain ${if (enabled) "restored" else "blocked"} for uid=$uid via INetworkManagementService")
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            failures += "INetworkManagementService: ${t.message}"
        }

        // 3. Ghi bền vững xuống /data/system/netpolicy.xml qua INetworkPolicyManager
        try {
            val netPolicy = getHookedService(
                ServiceBackend(
                    serviceName = "netpolicy",
                    stubClassName = "android.net.INetworkPolicyManager\$Stub",
                    label = "NetworkPolicyManager"
                )
            )
            val policy = if (enabled) POLICY_NONE else POLICY_REJECT_METERED_BACKGROUND
            callMethodResilient(netPolicy, listOf("setUidPolicy"), uid, policy)
            anySuccess = true
            Log.d(TAG, "Persistent netpolicy setUidPolicy(uid=$uid, policy=$policy) succeeded")
        } catch (t: Throwable) {
            failures += "INetworkPolicyManager: ${t.message}"
        }

        // 4. Fallback resilient method discovery nếu chưa thành công
        if (!anySuccess) {
            for (backend in serviceBackends) {
                try {
                    val service = getHookedService(backend)
                    if (!enabled) {
                        callMethodResilient(service, listOf("setFirewallChainEnabled"), OEM_DENY_CHAIN, true)
                    }
                    callMethodResilient(
                        service,
                        listOf("setUidFirewallRule", "setFirewallUidRule"),
                        OEM_DENY_CHAIN,
                        uid,
                        rule
                    )
                    anySuccess = true
                    break
                } catch (t: Throwable) {
                    failures += "${backend.label}: ${t.message}"
                }
            }
        }

        // 5. Nếu đang khóa (enabled = false), buộc dừng ngay tiến trình com.xiaomi.xmsf qua IActivityManager
        if (!enabled) {
            forceStopPackageViaBinder("com.xiaomi.xmsf")
        }

        if (!anySuccess) {
            Log.e(TAG, "No compatible firewall backend found for uid=$uid. ${failures.joinToString(" || ")}")
        }
        return anySuccess
    }

    /**
     * Buộc dừng gói ứng dụng trực tiếp qua IActivityManager Binder (đóng lập tức mọi socket mạng đang mở).
     */
    fun forceStopPackageViaBinder(packageName: String): Boolean {
        return try {
            val am = getHookedService(
                ServiceBackend(
                    serviceName = Context.ACTIVITY_SERVICE,
                    stubClassName = "android.app.IActivityManager\$Stub",
                    label = "ActivityManager"
                )
            )
            callMethodResilient(am, listOf("forceStopPackage"), packageName, 0)
            Log.d(TAG, "✓ forceStopPackage($packageName) via IActivityManager binder succeeded")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "forceStopPackageViaBinder($packageName) failed: ${t.message}")
            false
        }
    }

    private fun getHookedConnectivityManager(): Any {
        return getHookedService(
            ServiceBackend(
                serviceName = Context.CONNECTIVITY_SERVICE,
                stubClassName = "android.net.IConnectivityManager\$Stub",
                label = "ConnectivityManager"
            )
        )
    }

    private fun getHookedNetworkManagementService(): Any {
        return getHookedService(
            ServiceBackend(
                serviceName = "network_management",
                stubClassName = "android.os.INetworkManagementService\$Stub",
                label = "NetworkManagementService"
            )
        )
    }

    private fun getHookedService(backend: ServiceBackend): Any {
        hookedServiceCache[backend.stubClassName]?.let { return it }

        return synchronized(this) {
            hookedServiceCache[backend.stubClassName]?.let { return@synchronized it }

            val originalBinder = SystemServiceHelper.getSystemService(backend.serviceName)
                ?: throw IllegalStateException("${backend.label} binder is null")

            val wrapper: IBinder = ShizukuBinderWrapper(originalBinder)
            val stubClass = Class.forName(backend.stubClassName)
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val hooked = asInterface.invoke(null, wrapper) ?: throw IllegalStateException(
                "${backend.label}.Stub.asInterface returned null"
            )

            hookedServiceCache[backend.stubClassName] = hooked
            Log.i(TAG, "Created hooked ${backend.label} binder wrapper")
            hooked
        }
    }

    private fun callMethodResilient(target: Any, methodNames: List<String>, vararg args: Any): String {
        val methods = target.javaClass.methods.filter {
            it.name in methodNames && it.parameterCount == args.size
        }
        if (methods.isEmpty()) {
            throw NoSuchMethodException(
                "Could not find any of ${methodNames.joinToString()} with ${args.size} args on ${target.javaClass.name}"
            )
        }

        var lastError: Throwable? = null
        for (method in methods) {
            try {
                invokeMethod(target, method, args)
                return method.name
            } catch (t: Throwable) {
                lastError = t
            }
        }

        throw lastError ?: NoSuchMethodException(
            "Methods ${methodNames.joinToString()} exist on ${target.javaClass.name} but none accepted the arguments"
        )
    }

    private fun invokeMethod(target: Any, method: java.lang.reflect.Method, args: Array<out Any>) {
        method.isAccessible = true
        val adaptedArgs = Array(args.size) { index ->
            val arg = args[index]
            when (val expected = method.parameterTypes[index]) {
                Int::class.javaPrimitiveType -> when (arg) {
                    is Int -> arg
                    is Boolean -> if (arg) 1 else 0
                    is Number -> arg.toInt()
                    else -> throw IllegalArgumentException("Unsupported arg $arg for int parameter")
                }

                Boolean::class.javaPrimitiveType -> when (arg) {
                    is Boolean -> arg
                    is Number -> arg.toInt() != 0
                    else -> throw IllegalArgumentException("Unsupported arg $arg for boolean parameter")
                }

                String::class.java -> arg.toString()

                else -> if (expected.isInstance(arg)) arg
                else throw IllegalArgumentException("Arg $arg does not match ${expected.name}")
            }
        }

        try {
            method.invoke(target, *adaptedArgs)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
