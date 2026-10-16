package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.LogUtil
import java.net.HttpURLConnection
import java.net.URL

object TokenSync {
    // Adresse de ton serveur (à remplacer par ton VPS plus tard)
    const val BASE_URL = "http://127.0.0.1:8000"
    const val SUB_NAME = "VPN"
    private const val TAG = "TokenSync"

    @Volatile private var lastVer: String? = null
    @Volatile private var running = false
    @Volatile var onChanged: (() -> Unit)? = null
    @Volatile var onExpired: (() -> Unit)? = null

    private fun findSub() = MmkvManager.decodeSubscriptions()
        .firstOrNull { it.subscription.remarks == SUB_NAME }

    fun getToken(): String? =
        findSub()?.subscription?.url?.substringAfterLast("/sub/", "")?.ifEmpty { null }

    /** 1 = OK, 0 = échec */
    fun setToken(token: String): Int {
        val t = token.trim()
        if (t.isEmpty()) return 0
        val item = SubscriptionItem()
        item.remarks = SUB_NAME
        item.url = "$BASE_URL/sub/$t"
        item.enabled = true
        item.allowInsecureUrl = BASE_URL.startsWith("http://")
        MmkvManager.encodeSubscription(findSub()?.guid ?: "", item)
        lastVer = null
        return refresh()
    }

    fun refresh(): Int {
        val sub = findSub() ?: return 0
        val res = AngConfigManager.updateConfigViaSub(sub)
        return if (res.successCount > 0) 1 else 0
    }

    private fun http(path: String): Pair<Int, String> {
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        return try {
            val code = c.responseCode
            code to (if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else "")
        } finally {
            c.disconnect()
        }
    }

    fun start() {
        if (running) return
        running = true
        Thread {
            while (true) {
                try {
                    tick()
                } catch (e: Exception) {
                    LogUtil.e(TAG, "tick failed", e)
                }
                Thread.sleep(30_000)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun tick() {
        val token = getToken() ?: return
        val (code, body) = http("/ver/$token")
        if (code == 403) {
            onExpired?.invoke()
            return
        }
        if (code != 200) return
        val v = body.trim()
        if (v != lastVer) {
            val first = lastVer == null
            if (refresh() == 1) {
                lastVer = v
                if (!first) onChanged?.invoke()
            }
        }
    }
}
