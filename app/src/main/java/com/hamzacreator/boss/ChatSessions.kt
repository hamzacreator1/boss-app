package com.hamzacreator.boss

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One saved chat session. */
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long
)

/** Persists chat sessions in SharedPreferences as JSON. Simple, no DB needed. */
object ChatSessions {

    private const val PREFS = "BossSessions"
    private const val KEY = "sessions_json"

    fun list(context: Context): List<ChatSession> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        val out = mutableListOf<ChatSession>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val msgs = mutableListOf<ChatMessage>()
                val marr = o.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until marr.length()) {
                    val m = marr.getJSONObject(j)
                    msgs.add(
                        ChatMessage(
                            text = m.optString("text"),
                            isUser = m.optBoolean("isUser"),
                            time = m.optString("time")
                        )
                    )
                }
                out.add(
                    ChatSession(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        messages = msgs,
                        updatedAt = o.optLong("updatedAt")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun save(context: Context, session: ChatSession) {
        val current = list(context).toMutableList()
        current.removeAll { it.id == session.id }
        current.add(0, session.copy(updatedAt = System.currentTimeMillis()))
        persist(context, current.take(30)) // keep last 30
    }

    fun delete(context: Context, id: String) {
        val current = list(context).toMutableList()
        current.removeAll { it.id == id }
        persist(context, current)
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun titleFor(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.isUser }?.text?.trim().orEmpty()
        return if (firstUser.length > 32) firstUser.take(32) + "…" else firstUser.ifEmpty { "Nayi chat" }
    }

    private fun persist(context: Context, sessions: List<ChatSession>) {
        try {
            val arr = JSONArray()
            for (s in sessions) {
                val marr = JSONArray()
                for (m in s.messages) {
                    marr.put(
                        JSONObject()
                            .put("text", m.text)
                            .put("isUser", m.isUser)
                            .put("time", m.time)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("messages", marr)
                        .put("updatedAt", s.updatedAt)
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}
