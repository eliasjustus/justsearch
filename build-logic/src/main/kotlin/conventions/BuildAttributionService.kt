package conventions

import java.time.Instant
import java.util.Collections
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult

abstract class BuildAttributionService :
    BuildService<BuildAttributionService.Parameters>,
    OperationCompletionListener,
    AutoCloseable {

  interface Parameters : BuildServiceParameters {
    val outputFile: RegularFileProperty
    val rootDir: DirectoryProperty
  }

  private data class TaskTiming(
      val path: String,
      val displayName: String,
      val outcome: String,
      val startTimeMillis: Long,
      val endTimeMillis: Long,
      val durationMillis: Long,
      val skipped: Boolean,
      val upToDate: Boolean,
      val fromCache: Boolean,
      val failureCount: Int,
  )

  private val timings = Collections.synchronizedList(mutableListOf<TaskTiming>())

  override fun onFinish(event: FinishEvent) {
    if (event !is TaskFinishEvent) {
      return
    }

    val result = event.result
    val path = event.descriptor.taskPath
    val startTime = result.startTime
    val endTime = result.endTime
    val duration = (endTime - startTime).coerceAtLeast(0)

    val timing = when (result) {
      is TaskSuccessResult ->
          TaskTiming(
              path = path,
              displayName = event.descriptor.displayName,
              outcome = "success",
              startTimeMillis = startTime,
              endTimeMillis = endTime,
              durationMillis = duration,
              skipped = false,
              upToDate = result.isUpToDate,
              fromCache = result.isFromCache,
              failureCount = 0,
          )
      is TaskSkippedResult ->
          TaskTiming(
              path = path,
              displayName = event.descriptor.displayName,
              outcome = "skipped",
              startTimeMillis = startTime,
              endTimeMillis = endTime,
              durationMillis = duration,
              skipped = true,
              upToDate = false,
              fromCache = false,
              failureCount = 0,
          )
      is TaskFailureResult ->
          TaskTiming(
              path = path,
              displayName = event.descriptor.displayName,
              outcome = "failure",
              startTimeMillis = startTime,
              endTimeMillis = endTime,
              durationMillis = duration,
              skipped = false,
              upToDate = false,
              fromCache = false,
              failureCount = result.failures.size,
          )
      else ->
          TaskTiming(
              path = path,
              displayName = event.descriptor.displayName,
              outcome = result.javaClass.simpleName,
              startTimeMillis = startTime,
              endTimeMillis = endTime,
              durationMillis = duration,
              skipped = false,
              upToDate = false,
              fromCache = false,
              failureCount = 0,
          )
    }

    timings.add(timing)
  }

  override fun close() {
    val output = parameters.outputFile.get().asFile
    output.parentFile?.mkdirs()
    val snapshot = timings.toList().sortedWith(compareBy<TaskTiming> { it.startTimeMillis }.thenBy { it.path })
    output.writeText(renderJson(snapshot))
  }

  private fun renderJson(tasks: List<TaskTiming>): String {
    val root = parameters.rootDir.get().asFile.absolutePath
    val body = buildString {
      appendLine("{")
      appendLine("  \"kind\": \"justsearch-build-task-timing.v1\",")
      appendLine("  \"generatedAt\": \"${escape(Instant.now().toString())}\",")
      appendLine("  \"rootDir\": \"${escape(root)}\",")
      appendLine("  \"tasks\": [")
      tasks.forEachIndexed { index, task ->
        appendLine("    {")
        appendLine("      \"path\": \"${escape(task.path)}\",")
        appendLine("      \"displayName\": \"${escape(task.displayName)}\",")
        appendLine("      \"outcome\": \"${escape(task.outcome)}\",")
        appendLine("      \"startTimeMillis\": ${task.startTimeMillis},")
        appendLine("      \"endTimeMillis\": ${task.endTimeMillis},")
        appendLine("      \"durationMillis\": ${task.durationMillis},")
        appendLine("      \"skipped\": ${task.skipped},")
        appendLine("      \"upToDate\": ${task.upToDate},")
        appendLine("      \"fromCache\": ${task.fromCache},")
        appendLine("      \"failureCount\": ${task.failureCount}")
        append("    }")
        if (index != tasks.lastIndex) {
          append(",")
        }
        appendLine()
      }
      appendLine("  ]")
      appendLine("}")
    }
    return body
  }

  private fun escape(value: String): String =
      buildString {
        value.forEach { char ->
          when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
              if (char.code < 0x20) {
                append("\\u")
                append(char.code.toString(16).padStart(4, '0'))
              } else {
                append(char)
              }
            }
          }
        }
      }
}
