package com.example.macrorecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.macrorecorder.data.MacroSession
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onPlayClick: (MacroSession) -> Unit,
    private val onDeleteClick: (MacroSession) -> Unit
) : ListAdapter<MacroSession, SessionAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_session_name)
        val tvInfo: TextView = view.findViewById(R.id.tv_session_info)
        val btnPlay: MaterialButton = view.findViewById(R.id.btn_play)
        val btnDelete: MaterialButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = getItem(position)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        holder.tvName.text = session.name
        holder.tvInfo.text = "${dateFormat.format(Date(session.createdAt))}  ·  ${session.eventCount} 个操作"
        holder.btnPlay.setOnClickListener { onPlayClick(session) }
        holder.btnDelete.setOnClickListener { onDeleteClick(session) }
    }

    class DiffCallback : DiffUtil.ItemCallback<MacroSession>() {
        override fun areItemsTheSame(oldItem: MacroSession, newItem: MacroSession): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MacroSession, newItem: MacroSession): Boolean =
            oldItem == newItem
    }
}