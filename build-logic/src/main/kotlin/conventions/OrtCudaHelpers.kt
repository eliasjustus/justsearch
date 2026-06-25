package conventions

import java.io.File

/**
 * Shared ORT CUDA detection utilities for Gradle build scripts.
 *
 * Used by `modules/ui`, `modules/worker-core`, and `modules/indexer-worker` to auto-detect
 * the ORT CUDA native library directory at configuration time.
 */
object OrtCudaHelpers {

  /** CUDA subdirectory under `tmp/` containing ORT CUDA provider DLLs. */
  private const val CUDA_DIR_NAME = "tmp/ort-variant-test/cuda-12.4-v1.24.3"

  /** DLL whose presence confirms the CUDA directory is valid. */
  private const val CUDA_DLL_NAME = "onnxruntime_providers_cuda.dll"

  /**
   * Auto-detects ORT CUDA native library directory from the project root.
   *
   * Checks `[projectRoot]/tmp/ort-variant-test/cuda-12.4-v1.24.3/` for
   * `onnxruntime_providers_cuda.dll`. If the project root is a git worktree
   * (`.git` is a file, not a directory), also checks the main repo root.
   *
   * @param projectRoot the root project directory
   * @return absolute path to the detected CUDA directory, or null if not found
   */
  fun detectOrtCudaPath(projectRoot: File): String? {
    val candidates = mutableListOf(projectRoot)
    val gitFile = projectRoot.resolve(".git")
    if (gitFile.isFile) {
      val gitdirLine = gitFile.readText().trim()
      if (gitdirLine.startsWith("gitdir:")) {
        val gitdir = File(gitdirLine.removePrefix("gitdir:").trim())
        // .git/worktrees/<name> → repo root is two levels up from gitdir
        val repoRoot = gitdir.parentFile?.parentFile?.parentFile
        if (repoRoot != null && repoRoot != projectRoot && repoRoot.exists()) {
          candidates.add(repoRoot)
        }
      }
    }
    for (candidate in candidates) {
      val cudaDir = candidate.resolve(CUDA_DIR_NAME)
      if (cudaDir.resolve(CUDA_DLL_NAME).exists()) {
        return cudaDir.absolutePath
      }
    }
    return null
  }
}
