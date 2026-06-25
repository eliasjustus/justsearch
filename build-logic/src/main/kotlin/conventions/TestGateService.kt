package conventions

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A shared build service that gates test task parallelism.
 * With maxParallelUsages=1, only one Test task runs at a time
 * even when org.gradle.parallel=true, preventing heavyweight
 * test JVM memory contention.
 */
abstract class TestGateService : BuildService<BuildServiceParameters.None>
