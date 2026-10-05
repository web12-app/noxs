/*
 * Noxs — original implementation.
 * Small shared UI helpers: a two-line RecyclerView adapter used by all
 * manager screens.
 */
package com.crossberry.noxs.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crossberry.noxs.R

data class TwoLineRow(
    val title: String,
    val subtitle: String = "",
    val onClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null
)

class TwoLineAdapter(private var rows: List<TwoLineRow> = emptyList()) :
    RecyclerView.Adapter<TwoLineAdapter.VH>() {

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.row_title)
        val subtitle: TextView = v.findViewById(R.id.row_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_two_line, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.itemView.setOnClickListener { row.onClick?.invoke() }
        holder.itemView.setOnLongClickListener { row.onLongClick?.invoke(); true }
    }

    fun submit(newRows: List<TwoLineRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    companion object {
        fun bind(recycler: RecyclerView, rows: List<TwoLineRow>): TwoLineAdapter {
            val adapter = TwoLineAdapter(rows)
            recycler.layoutManager = LinearLayoutManager(recycler.context)
            recycler.adapter = adapter
            return adapter
        }
    }
}
