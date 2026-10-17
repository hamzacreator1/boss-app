package com.hamzacreator.boss

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hamzacreator.boss.databinding.ItemChatMessageBinding

/** One chat bubble. */
data class ChatMessage(val text: String, val isUser: Boolean)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()

    inner class VH(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        val b = holder.binding
        b.tvMessage.text = msg.text
        if (msg.isUser) {
            b.messageRoot.gravity = Gravity.END
            b.spaceView.visibility = View.GONE
            b.cardMessage.setCardBackgroundColor(Color.parseColor("#1E3A8A"))
            b.tvMessage.setTextColor(Color.WHITE)
        } else {
            b.messageRoot.gravity = Gravity.START
            b.spaceView.visibility = View.VISIBLE
            b.cardMessage.setCardBackgroundColor(Color.parseColor("#1A2340"))
            b.tvMessage.setTextColor(Color.WHITE)
        }
    }

    override fun getItemCount(): Int = items.size

    /** Adds a message, returns its position. */
    fun addMessage(msg: ChatMessage): Int {
        items.add(msg)
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    /** Replaces the text of an existing message (e.g. finished stream). */
    fun updateMessage(position: Int, text: String) {
        if (position in items.indices) {
            items[position] = items[position].copy(text = text)
            notifyItemChanged(position)
        }
    }

    /** Appends streamed text to an existing message. */
    fun appendToMessage(position: Int, chunk: String) {
        if (position in items.indices) {
            val cur = items[position]
            items[position] = cur.copy(text = cur.text + chunk)
            notifyItemChanged(position)
        }
    }
}
