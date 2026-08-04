package com.swarmbuilder.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.swarmbuilder.app.R
import com.swarmbuilder.app.models.LogLevel
import com.swarmbuilder.app.models.SwarmLog

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val items = mutableListOf<SwarmLog>()

    fun add(log: SwarmLog) {
        items.add(log)
        notifyItemInserted(items.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(v)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAgent: TextView = itemView.findViewById(R.id.tv_agent)
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(log: SwarmLog) {
            tvAgent.text = "[${log.agentName}]"
            tvMessage.text = log.message
            val color = when (log.level) {
                LogLevel.SUCCESS -> 0xFF2E7D32.toInt()
                LogLevel.WARNING -> 0xFFE65100.toInt()
                LogLevel.ERROR -> 0xFFB71C1C.toInt()
                LogLevel.INFO -> 0xFF212121.toInt()
            }
            tvMessage.setTextColor(color)
        }
    }
}
