package com.unyx.atlas.feature.chat

import com.unyx.atlas.memory.MemoryProject
import com.unyx.atlas.memory.MemoryTask
import java.time.LocalDate

/**
 * The goal-deadline half of the proactive nudges — one source of truth shared by
 * [ChatViewModel.computeProactiveNudges] (chat open) and [ProactiveNudgeWorker] (app closed), so
 * the in-app card and the system notification can never disagree about what is late.
 *
 * CRITICAL (overdue) vs ATTENTION (due today) — the Attention Model's own levels (section 19).
 * Only active projects count; tasks are expected to be pre-filtered to the open ones.
 */
internal fun deadlineNudges(
    tasks: List<MemoryTask>,
    projects: List<MemoryProject>,
    today: LocalDate,
): List<ProactiveNudge> {
    val taskDeadlines = tasks.mapNotNull { task ->
        task.prazo?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { task.titulo to it }
    }
    val projectDeadlines = projects.filter { it.status == "ativo" }.mapNotNull { project ->
        project.deadline?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { project.nome to it }
    }
    val allDeadlines = taskDeadlines + projectDeadlines
    val overdue = allDeadlines.filter { (_, deadline) -> deadline.isBefore(today) }
    val dueToday = allDeadlines.filter { (_, deadline) -> deadline.isEqual(today) }

    val nudges = mutableListOf<ProactiveNudge>()
    if (overdue.isNotEmpty()) {
        nudges += ProactiveNudge(
            text = if (overdue.size == 1) {
                "Você tem algo atrasado: ${overdue.first().first}"
            } else {
                "Você tem ${overdue.size} coisas atrasadas (tarefas ou projetos)."
            },
            prompt = "O que está atrasado ou vencendo?",
            level = ProactiveLevel.CRITICAL,
            sourceKey = "goal_deadline_overdue",
        )
    }
    if (dueToday.isNotEmpty()) {
        nudges += ProactiveNudge(
            text = if (dueToday.size == 1) {
                "Você tem algo vencendo hoje: ${dueToday.first().first}"
            } else {
                "Você tem ${dueToday.size} coisas vencendo hoje."
            },
            prompt = "O que está atrasado ou vencendo?",
            level = ProactiveLevel.ATTENTION,
            sourceKey = "goal_deadline",
        )
    }
    return nudges
}
