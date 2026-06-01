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
    private val onProxyDelete: (ProxyEntry) -> Unit
) : RecyclerView.Adapter<ProxyAdapter.ProxyViewHolder>() {

    fun updateData(newProxies: List<ProxyEntry>) {
        proxies = newProxies
        notifyDataSetChanged()
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
        private val rbSelected = view.findViewById<RadioButton>(R.id.rb_proxy_selected)
        private val tvAddress = view.findViewById<TextView>(R.id.tv_proxy_address)
        private val tvPing = view.findViewById<TextView>(R.id.tv_proxy_ping)
        private val btnEdit = view.findViewById<ImageButton>(R.id.btn_proxy_edit)
        private val btnDelete = view.findViewById<ImageButton>(R.id.btn_proxy_delete)

        fun bind(proxy: ProxyEntry) {
            tvAddress.text = "${proxy.host}:${proxy.port}"
            rbSelected.isChecked = proxy.isEnabled
            tvPing.visibility = View.GONE

            rbSelected.setOnClickListener { onProxySelected(proxy) }
            itemView.setOnClickListener { onProxySelected(proxy) }
            btnEdit.setOnClickListener { onProxyEdit(proxy) }
            btnDelete.setOnClickListener { onProxyDelete(proxy) }
        }
    }
}
