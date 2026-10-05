/*
 * Noxs — original implementation.
 * Adapter for the Activity Center list: section headers + activity rows.
 */
package com.crossberry.noxs.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crossberry.noxs.R
import com.crossberry.noxs.runtime.NoxsActivityRecord

sealed class ActivityRow {
    data class Header(val label: String) : ActivityRow()
    data class Item(val record: NoxsActivityRecord, val meta1: String, val meta2: String) : ActivityRow()
}

class NoxsActivityAdapter(
    private var rows: List<ActivityRow>,
    private val onItemClick: (NoxsActivityRecord) -> Unit
) : RecyclerView.Adapter<NoxsActivityAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val status: TextView = v.findViewById(R.id.item_status)
        val title: TextView = v.findViewById(R.id.item_title)
        val meta1: TextView = v.findViewById(R.id.item_meta1)
        val meta2: TextView = v.findViewById(R.id.item_meta2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_activity, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        when (row) {
            is ActivityRow.Header -> {
                holder.status.text = row.label
                holder.status.setTextColor(0xFF8A94A0.toInt())
                holder.title.text = ""
                holder.meta1.text = ""
                holder.meta2.text = ""
                holder.itemView.setOnClickListener(null)
            }
            is ActivityRow.Item -> {
                val record = row.record
                holder.status.text = FloatingStatusPanel.statusText(record.status)
                holder.status.setTextColor(FloatingStatusPanel.statusColor(record.status))
                holder.title.text = record.title
                holder.title.typeface = Typeface.MONOSPACE
                holder.meta1.text = row.meta1
                holder.meta2.text = row.meta2
                holder.itemView.setOnClickListener { onItemClick(record) }
            }
        }
    }

    fun submit(newRows: List<ActivityRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    companion object {
        fun bind(recycler: RecyclerView, rows: List<ActivityRow>, onClick: (NoxsActivityRecord) -> Unit): NoxsActivityAdapter {
            val adapter = NoxsActivityAdapter(rows, onClick)
            recycler.layoutManager = LinearLayoutManager(recycler.context)
            recycler.adapter = adapter
            return adapter
        }
    }
}
