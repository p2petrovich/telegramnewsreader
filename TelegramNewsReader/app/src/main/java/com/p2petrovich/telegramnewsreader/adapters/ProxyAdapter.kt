package com.p2petrovich.telegramnewsreader.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.models.ProxyEntry

class ProxyAdapter(
    private var proxies: List<ProxyEntry>,
    private val onProxySelected: (ProxyEntry) -> Unit,
    private val onProxyEdit: (ProxyEntry) -> Unit,
    private val onProxyTest: (ProxyEntry, (String, Int) -> Unit) -> Unit
) : RecyclerView.Adapter<ProxyAdapter.ProxyViewHolder>() {

    private val pings = mutableMapOf<String, String>()
    private val pingColors = mutableMapOf<String, Int>()

    fun updateData(newProxies: List<ProxyEntry>) {
        proxies = newProxies
        notifyDataSetChanged()
    }

    fun updatePing(proxyId: String, status: String, color: Int) {
        pings[proxyId] = status
        pingColors[proxyId] = color
        val index = proxies.indexOfFirst { it.id == proxyId }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_proxy, parent, false)
        return ProxyViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProxyViewHolder, position: Int) {
        holder.bind(proxies[position])
    }

    override fun getItemCount(): Int = proxies.size

    inner class ProxyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvAddress = view.findViewById<TextView>(R.id.tv_proxy_address)
        private val tvPing = view.findViewById<TextView>(R.id.tv_proxy_ping)
        private val btnEdit = view.findViewById<ImageButton>(R.id.btn_proxy_edit)
        private val ivActive = view.findViewById<android.widget.ImageView>(R.id.iv_proxy_active)

        fun bind(proxy: ProxyEntry) {
            tvAddress.text = "${proxy.host}:${proxy.port}"
            
            val status = pings[proxy.id] ?: (if (proxy.isEnabled) "Соединение..." else "Недоступен")
            val color = pingColors[proxy.id] ?: (if (proxy.isEnabled) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt())
            
            tvPing.text = status
            tvPing.setTextColor(color)
            tvPing.visibility = View.VISIBLE

            ivActive.visibility = if (proxy.isEnabled) View.VISIBLE else View.GONE
            // Если активно, добавляем отступ тексту для галочки
            val params = tvAddress.layoutParams as ViewGroup.MarginLayoutParams
            params.marginStart = if (proxy.isEnabled) (8 * itemView.resources.displayMetrics.density).toInt() else 0
            tvAddress.layoutParams = params

            itemView.setOnClickListener { onProxySelected(proxy) }
            btnEdit.setOnClickListener { onProxyEdit(proxy) }
        }
    }
}
