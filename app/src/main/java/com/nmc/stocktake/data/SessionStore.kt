package com.nmc.stocktake.data

import android.content.Context
import com.nmc.stocktake.core.StockSheet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the list being worked on in a file on the phone, rewritten after
 * every change. Closing the app, a phone call or a flat battery loses at
 * most the item that was being typed; opening the app again carries on
 * where it stopped.
 */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "session.json")

    /** webDelivery: the web app's delivery the receiving list came from, if any. */
    data class Session(val sheet: StockSheet, val index: Int, val source: String, val webDelivery: String = "")

    fun save(sheet: StockSheet, index: Int, source: String, webDelivery: String = "") {
        val items = JSONArray()
        for (it in sheet.items) {
            items.put(JSONObject().apply {
                put("code", it.code); put("name", it.name); put("group", it.group); put("note", it.note)
                put("onSystem", it.onSystem ?: JSONObject.NULL)
                put("countText", it.countText)
                put("counted", it.counted ?: JSONObject.NULL)
                put("expiry", it.expiry); put("batch", it.batch)
                put("fileExpiry", it.fileExpiry); put("fileBatch", it.fileBatch)
                put("qty", it.qty); put("packSize", it.packSize); put("packPrice", it.packPrice)
                put("location", it.location); put("fileLocation", it.fileLocation)
                put("barcode", it.barcode); put("fileBarcode", it.fileBarcode)
                put("line", it.line); put("invoiceQty", it.invoiceQty); put("countedBy", it.countedBy)
                put("done", it.done)
                put("invoiceName", it.invoiceName); put("onList", it.onList)
            })
        }
        val root = JSONObject().apply {
            put("mode", sheet.mode.name)
            put("index", index)
            put("source", source)
            put("webDelivery", webDelivery)
            put("items", items)
        }
        // Write to a spare file first, then swap it in, so a crash halfway
        // through writing never leaves a broken file behind.
        val tmp = File(file.parentFile, "session.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    fun load(): Session? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("items")
            val items = ArrayList<StockSheet.Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(StockSheet.Item().apply {
                    code = o.optString("code"); name = o.optString("name")
                    group = o.optString("group"); note = o.optString("note")
                    onSystem = if (o.isNull("onSystem")) null else o.getInt("onSystem")
                    countText = o.optString("countText")
                    counted = if (o.isNull("counted")) null else o.getInt("counted")
                    expiry = o.optString("expiry"); batch = o.optString("batch")
                    fileExpiry = o.optString("fileExpiry"); fileBatch = o.optString("fileBatch")
                    qty = o.optString("qty"); packSize = o.optString("packSize"); packPrice = o.optString("packPrice")
                    location = o.optString("location"); fileLocation = o.optString("fileLocation")
                    barcode = o.optString("barcode"); fileBarcode = o.optString("fileBarcode")
                    line = o.optString("line"); invoiceQty = o.optString("invoiceQty")
                    countedBy = o.optString("countedBy")
                    done = o.optBoolean("done")
                    invoiceName = o.optString("invoiceName"); onList = o.optString("onList")
                })
            }
            val sheet = StockSheet(StockSheet.Mode.valueOf(root.getString("mode")), items)
            Session(sheet, root.optInt("index").coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                root.optString("source"), root.optString("webDelivery"))
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        file.delete()
    }
}
