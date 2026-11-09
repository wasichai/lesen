package lesen.progress

import lesen.common.Access
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeParseException

// el progreso de quien pregunta: panel, resumen semanal, logros y metas. Todo es personal (cuaderno); sin perfil, 404
@RestController
@RequestMapping("/api/lesen/progress")
class ProgressController(
    private val access: Access,
    private val service: ProgressService,
) {
    @GetMapping("/dashboard")
    suspend fun dashboard(
        @RequestParam language: String?,
    ): Dashboard = service.dashboard(access.notebook(), language)

    // ?week=AAAA-MM-DD: la semana que contiene ese día; sin él, la en curso
    @GetMapping("/weekly")
    suspend fun weekly(
        @RequestParam week: String?,
    ): WeeklySummary {
        val day =
            week?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    throw ValidationException("Invalid week", "week", "must be a date like 2026-11-09")
                }
            }
        return service.weekly(access.notebook(), day)
    }

    @GetMapping("/achievements")
    suspend fun achievements(): List<AchievementView> = service.achievements(access.notebook())

    @GetMapping("/goals")
    suspend fun goals(): Goals = service.goals(access.notebook())

    @PutMapping("/goals")
    suspend fun saveGoals(
        @RequestBody request: GoalsRequest,
    ): Goals = service.saveGoals(access.writeNotebook(), request)
}
