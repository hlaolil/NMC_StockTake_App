package com.nmc.stocktake.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The link to the NMC web app: its address and the key the web app showed
 * as a QR code (Stock Take page > link the phone app). With it the phone
 * gets count sheets and sends counts directly, instead of passing files.
 *
 * Counts sent here wait on the web Stock Take page as uploaded figures;
 * nothing is posted until someone presses Post there.
 */
class WebLink(context: Context) {
    private val prefs = context.getSharedPreferences("web_link", Context.MODE_PRIVATE)

    val address: String? get() = prefs.getString("address", null)
    private val key: String? get() = prefs.getString("key", null)
    val isLinked: Boolean get() = !address.isNullOrBlank() && !key.isNullOrBlank()

    /** Read the code from the QR (or typed): "NMCST1|https://site|key". */
    fun link(code: String): String? {
        val parts = code.trim().split("|")
        if (parts.size != 3 || parts[0] != PREFIX) return "That is not a link code from the NMC web app"
        val addr = parts[1].trim().trimEnd('/')
        if (!addr.startsWith("https://") && !addr.startsWith("http://")) return "The link code has no web address"
        if (parts[2].length < 20) return "The link code is incomplete"
        prefs.edit().putString("address", addr).putString("key", parts[2].trim()).apply()
        return null
    }

    fun unlink() {
        prefs.edit().clear().apply()
    }

    data class Group(val group: String, val label: String, val toCount: Int)
    data class Open(val reference: String, val groups: String, val toCount: Int, val byGroup: List<Group>)

    /** What is open to count on the web app, or null if nothing is. */
    suspend fun hello(): Open? {
        val j = JSONObject(call("GET", "/api/phone/hello", null))
        val st = j.optJSONObject("stock_take") ?: return null
        val arr = st.getJSONArray("by_group")
        val groups = (0 until arr.length()).map {
            val g = arr.getJSONObject(it)
            Group(g.getString("group"), g.getString("label"), g.getInt("to_count"))
        }
        return Open(st.getString("reference"), st.getString("groups"), st.getInt("to_count"), groups)
    }

    /** The count sheet CSV, all of it ("") or one group. */
    suspend fun sheet(group: String): String =
        call("GET", "/api/phone/sheet" + if (group.isBlank()) "" else "?group=" + URLEncoder.encode(group, "UTF-8"), null)

    data class Delivery(val id: String, val reference: String, val supplier: String, val invoices: String,
                        val lines: Int, val checked: Int)

    /** Open deliveries on the web app. */
    suspend fun deliveries(): List<Delivery> {
        val arr = JSONObject(call("GET", "/api/phone/deliveries", null)).getJSONArray("deliveries")
        return (0 until arr.length()).map {
            val d = arr.getJSONObject(it)
            Delivery(d.getString("id"), d.getString("reference"), d.optString("supplier"),
                d.optString("invoices"), d.optInt("lines"), d.optInt("checked"))
        }
    }

    /** A delivery's uploaded invoice lines, as CSV. */
    suspend fun deliveryLines(id: String): String =
        call("GET", "/api/phone/delivery/" + URLEncoder.encode(id, "UTF-8") + "/lines", null)

    /** Send the lines as checked; returns the web app's message. */
    suspend fun sendDelivery(id: String, csv: String): String =
        JSONObject(call("POST", "/api/phone/delivery/" + URLEncoder.encode(id, "UTF-8") + "/lines", csv))
            .optString("message", "Sent.")

    /** Send counts; returns the web app's message. */
    suspend fun send(csv: String): String =
        JSONObject(call("POST", "/api/phone/counts", csv)).optString("message", "Sent.")

    class Refused(message: String) : Exception(message)

    private suspend fun call(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val addr = address ?: throw Refused("This phone is not linked to the web app")
        val conn = (URL(addr + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json, text/csv")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "text/csv; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { "" }
                throw Refused(msg.ifBlank { "The web app answered $code" })
            }
            text.removePrefix("﻿")
        } catch (e: IOException) {
            throw Refused("Could not reach the web app - check the signal and try again")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val PREFIX = "NMCST1"
    }
}
