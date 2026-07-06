package com.p2petrovich.telegramnewsreader.fragments

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.adapters.ProxyAdapter
import com.p2petrovich.telegramnewsreader.models.ProxyEntry
import com.p2petrovich.telegramnewsreader.telegram.TelegramClient
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager

class ProxySettingsDialogFragment : DialogFragment() {

    private var telegramClient: TelegramClient? = null
    private var onDismissListener: (() -> Unit)? = null
    
    private var adapter: ProxyAdapter? = null
    private var proxies = mutableListOf<ProxyEntry>()
    private lateinit var tvEmpty: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var layoutAuto: LinearLayout

    fun setTelegramClient(client: TelegramClient) {
        this.telegramClient = client
    }

    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val inflater = activity.layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_proxy_settings, null)

        val swEnabled = dialogView.findViewById<MaterialSwitch>(R.id.switch_proxy_enabled)
        recycler = dialogView.findViewById(R.id.recycler_proxies)
        tvEmpty = dialogView.findViewById(R.id.tv_proxies_empty)
        val btnAdd = dialogView.findViewById<Button>(R.id.btn_add_proxy)
        layoutAuto = dialogView.findViewById(R.id.layout_auto_switch_settings)
        val swAuto = dialogView.findViewById<MaterialSwitch>(R.id.switch_auto_proxy)
        val spinnerInterval = dialogView.findViewById<Spinner>(R.id.spinner_proxy_interval)

        proxies = PreferenceManager.getProxyList(activity).toMutableList()

        swEnabled.isChecked = PreferenceManager.isProxyEnabled(activity)
        swAuto.isChecked = PreferenceManager.isProxyAutoSwitchEnabled(activity)

        val intervals = listOf(5, 10, 15, 30, 60)
        val intervalAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, intervals.map { "$it ${getString(R.string.min_short)}" })
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = intervalAdapter
        spinnerInterval.setSelection(intervals.indexOf(PreferenceManager.getProxySwitchInterval(activity)).coerceAtLeast(0))

        adapter = ProxyAdapter(
            proxies = proxies,
            onProxySelected = { selected ->
                proxies.forEach { it.isEnabled = it.id == selected.id }
                PreferenceManager.saveProxyList(activity, proxies)
                adapter?.updateData(proxies)
                telegramClient?.applyProxySettings()
            },
            onProxyEdit = { proxy ->
                showAddEditProxyDialog(proxy) { updated ->
                    val idx = proxies.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) {
                        proxies[idx] = updated
                        PreferenceManager.saveProxyList(activity, proxies)
                        adapter?.updateData(proxies)
                        updateEmptyState()
                        testAllProxies()
                    }
                }
            },
            onProxyTest = { _, _ -> }
        )

        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        updateEmptyState()
        testAllProxies()

        btnAdd.setOnClickListener {
            showAddEditProxyDialog(null) { newProxy ->
                if (proxies.isEmpty()) newProxy.isEnabled = true
                proxies.add(newProxy)
                PreferenceManager.saveProxyList(activity, proxies)
                adapter?.updateData(proxies)
                updateEmptyState()
                testAllProxies()
            }
        }

        return AlertDialog.Builder(activity)
            .setTitle(R.string.mtproto_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                PreferenceManager.setProxyEnabled(activity, swEnabled.isChecked)
                PreferenceManager.setProxyAutoSwitchEnabled(activity, swAuto.isChecked)
                PreferenceManager.setProxySwitchInterval(activity, intervals[spinnerInterval.selectedItemPosition])

                telegramClient?.applyProxySettings()
                Toast.makeText(activity, getString(R.string.proxy_updated), Toast.LENGTH_SHORT).show()
                onDismissListener?.invoke()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onDismissListener?.invoke() }
            .create()
    }

    private fun updateEmptyState() {
        if (proxies.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            layoutAuto.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            layoutAuto.visibility = if (proxies.size >= 2) View.VISIBLE else View.GONE
        }
    }

    private fun testAllProxies() {
        val activity = activity ?: return
        proxies.forEach { proxy ->
            telegramClient?.testProxy(proxy.host, proxy.port, proxy.secret) { ping, error ->
                activity.runOnUiThread {
                    if (ping != null) {
                        val pingMs = (ping * 1000).toInt()
                        val status = getString(R.string.proxy_connected_ping, pingMs)
                        val color = 0xFF4CAF50.toInt()
                        adapter?.updatePing(proxy.id, status, color)
                    } else {
                        val status = if (error != null) getString(R.string.proxy_unavailable_reason, error) else getString(R.string.proxy_unavailable)
                        val color = 0xFFFF5252.toInt()
                        adapter?.updatePing(proxy.id, status, color)
                    }
                }
            }
        }
    }

    private fun showAddEditProxyDialog(proxy: ProxyEntry?, onSaved: (ProxyEntry) -> Unit) {
        val activity = requireActivity()
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_proxy_add, null)
        val etHost = dialogView.findViewById<EditText>(R.id.et_proxy_host)
        val etPort = dialogView.findViewById<EditText>(R.id.et_proxy_port)
        val etSecret = dialogView.findViewById<EditText>(R.id.et_proxy_secret)
        val btnPaste = dialogView.findViewById<Button>(R.id.btn_paste_inline)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_delete_proxy_inline)

        if (proxy != null) {
            etHost.setText(proxy.host)
            etPort.setText(proxy.port.toString())
            etSecret.setText(proxy.secret)
            btnDelete.visibility = View.VISIBLE
        }

        val alertDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.proxy_server)
            .setView(dialogView)
            .setPositiveButton(R.string.done) { _, _ ->
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: 0
                val secret = etSecret.text.toString().trim()

                if (host.isNotEmpty() && port > 0 && secret.isNotEmpty()) {
                    val updated = proxy?.copy(host = host, port = port, secret = secret)
                        ?: ProxyEntry(host = host, port = port, secret = secret)
                    onSaved(updated)
                } else {
                    Toast.makeText(activity, getString(R.string.save_proxy_error), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        btnPaste.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.startsWith("tg://proxy?") || text.startsWith("https://t.me/proxy?")) {
                val uri = Uri.parse(text.replace("tg://proxy", "https://t.me/proxy"))
                etHost.setText(uri.getQueryParameter("server") ?: "")
                etPort.setText(uri.getQueryParameter("port") ?: "")
                etSecret.setText(uri.getQueryParameter("secret") ?: "")
            } else {
                Toast.makeText(activity, getString(R.string.clipboard_no_proxy), Toast.LENGTH_SHORT).show()
            }
        }

        btnDelete.setOnClickListener {
            if (proxy != null) {
                val currentProxies = PreferenceManager.getProxyList(activity).toMutableList()
                currentProxies.removeAll { it.id == proxy.id }
                PreferenceManager.saveProxyList(activity, currentProxies)
                
                // Update local state and UI
                this.proxies.clear()
                this.proxies.addAll(currentProxies)
                adapter?.updateData(this.proxies)
                updateEmptyState()
                
                alertDialog.dismiss()
                Toast.makeText(activity, activity.getString(R.string.selection_cleared), Toast.LENGTH_SHORT).show()
            }
        }

        alertDialog.show()
    }
}
