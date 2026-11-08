import org.gradle.api.artifacts.ProjectDependency

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Dependency-rule check for architecture §3.3. Every module has an explicit allow-list of
 * project dependencies; anything outside it fails the build.
 */
val dependencyAllowlist: Map<String, Set<String>> = mapOf(
    ":app" to setOf(
        ":ui", ":service", ":sync", ":playback", ":voice", ":communication", ":audio",
        ":music:api", ":music:youtube", ":core:model", ":core:common", ":core:network",
        ":core:data", ":core:designsystem", ":protocol", ":testing",
    ),
    ":ui" to setOf(
        ":sync", ":playback", ":voice", ":communication", ":audio", ":music:api",
        ":core:model", ":core:common", ":core:data", ":core:designsystem", ":testing",
    ),
    ":service" to setOf(
        ":playback", ":sync", ":voice", ":communication", ":audio", ":music:api",
        ":core:model", ":core:common", ":core:data", ":core:network", ":protocol", ":testing",
    ),
    ":sync" to setOf(
        ":core:model", ":core:common", ":core:network", ":protocol",
    ),
    ":playback" to setOf(
        ":sync", ":music:api", ":audio", ":core:model", ":core:common", ":core:network", ":protocol",
    ),
    ":voice" to setOf(
        ":core:model", ":core:common", ":core:network", ":protocol", ":audio",
    ),
    ":communication" to setOf(
        ":core:model", ":core:common", ":core:network", ":core:data", ":protocol", ":audio", ":sync",
    ),
    ":audio" to setOf(":core:model", ":core:common"),
    ":music:youtube" to setOf(":music:api", ":core:model", ":core:common", ":core:network", ":protocol"),
    ":music:api" to setOf(":core:model", ":core:common"),
    ":core:network" to setOf(":protocol", ":core:model", ":core:common", ":core:data"),
    ":core:data" to setOf(":core:model", ":core:common", ":protocol"),
    ":core:designsystem" to setOf(":core:model", ":core:common"),
    ":core:model" to emptySet(),
    ":core:common" to emptySet(),
    ":protocol" to setOf(":core:model", ":core:common"),
    ":backend" to setOf(":protocol", ":core:model", ":core:common"),
    ":testing" to setOf(
        ":protocol", ":core:model", ":core:common", ":sync", ":music:api", ":audio", ":voice",
        ":communication", ":playback",
    ),
)

val pureJvmModules = setOf(":protocol", ":core:model", ":core:common", ":backend", ":music:api", ":music:youtube")

val dependencyRules = tasks.register("dependencyRules") {
    group = "verification"
    description = "Validates architecture §3.3 dependency edges and Android-free JVM modules."
    doLast {
        val violations = mutableListOf<String>()
        allprojects {
            val consumer = path
            val allowed = dependencyAllowlist[consumer]
            if (allowed != null) {
                configurations.forEach { configuration ->
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .forEach { dependency ->
                            val target = dependency.path
                            if (target != consumer && target !in allowed) {
                                violations += "$consumer depends on $target (configuration ${configuration.name})"
                            }
                        }
                }
            }
            if (consumer in pureJvmModules) {
                val androidPlugins = listOf("com.android.application", "com.android.library")
                if (androidPlugins.any { pluginManager.hasPlugin(it) }) {
                    violations += "$consumer must remain Android-free but applies an Android plugin"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Dependency rule violations:\n" + violations.distinct().joinToString("\n"))
        }
        logger.lifecycle("Dependency rules: OK (${dependencyAllowlist.size} modules checked)")
    }
}

gradle.projectsEvaluated {
    val checks = subprojects.mapNotNull { it.tasks.findByName("check") }
    tasks.named("check") {
        dependsOn(checks)
        dependsOn(dependencyRules)
    }
    subprojects {
        val subCheck = tasks.findByName("check") ?: return@subprojects
        subCheck.dependsOn(dependencyRules)
    }
}
