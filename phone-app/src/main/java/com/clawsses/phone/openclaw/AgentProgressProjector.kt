package com.clawsses.phone.openclaw

import com.clawsses.shared.AgentProgressUpdate
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Projects gateway agent events into small, privacy-safe HUD status lines. */
object AgentProgressProjector {
    private const val MAX_LABEL_CHARS = 96

    fun project(stream: String, data: JsonObject?): AgentProgressUpdate? = when (stream) {
        "thinking", "reasoning" -> AgentProgressUpdate(
            id = "reasoning",
            kind = "reasoning",
            label = "Reasoning…",
        )
        "tool" -> projectTool(data)
        "plan" -> projectPlan(data)
        "run_status" -> AgentProgressUpdate(
            id = "run-status",
            kind = "status",
            label = "Starting agent…",
        )
        else -> null
    }

    private fun projectTool(data: JsonObject?): AgentProgressUpdate? {
        data ?: return null
        val phase = data.string("phase") ?: return null
        val name = data.string("name") ?: "tool"
        val callId = data.string("toolCallId") ?: name
        val action = friendlyToolAction(name)
        return when (phase) {
            "start", "update" -> AgentProgressUpdate(
                id = "tool:${stableId(callId)}",
                kind = "tool",
                label = "$action…",
                state = "active",
            )
            "result" -> AgentProgressUpdate(
                id = "tool:${stableId(callId)}",
                kind = "tool",
                label = if (data.boolean("isError") == true) "$action failed" else "$action complete",
                state = if (data.boolean("isError") == true) "error" else "done",
            )
            else -> null
        }
    }

    private fun projectPlan(data: JsonObject?): AgentProgressUpdate? {
        val steps = data?.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val parsed = steps.mapNotNull(::parsePlanStep)
        val active = parsed.firstOrNull { it.second == "in_progress" }
            ?: parsed.firstOrNull { it.second == "pending" }
            ?: parsed.lastOrNull()
            ?: return null
        val allDone = parsed.isNotEmpty() && parsed.all { it.second == "completed" }
        return AgentProgressUpdate(
            id = "plan",
            kind = "plan",
            label = sanitizeLabel(active.first),
            state = if (allDone) "done" else "active",
        )
    }

    private fun parsePlanStep(element: JsonElement): Pair<String, String>? {
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return sanitizeLabel(element.asString).takeIf { it.isNotBlank() }?.let { it to "pending" }
        }
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val step = sanitizeLabel(obj.string("step") ?: return null)
        if (step.isBlank()) return null
        return step to (obj.string("status") ?: "pending")
    }

    private fun friendlyToolAction(name: String): String {
        val normalized = name.lowercase()
        return when {
            normalized.contains("search") || normalized.contains("fetch") -> "Searching"
            normalized.contains("read") || normalized.contains("memory") -> "Reading"
            normalized.contains("image") || normalized.contains("photo") -> "Processing image"
            normalized.contains("exec") || normalized.contains("bash") ||
                normalized.contains("terminal") || normalized.contains("command") -> "Running command"
            normalized.contains("write") || normalized.contains("patch") || normalized.contains("edit") -> "Updating files"
            else -> "Using tool"
        }
    }

    private fun sanitizeLabel(value: String): String = value
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_LABEL_CHARS)

    private fun stableId(value: String): String = Integer.toUnsignedString(value.hashCode(), 16)

    private fun JsonObject.string(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.boolean(key: String): Boolean? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean
}
