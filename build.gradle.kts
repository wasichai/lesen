import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.ktlint)
}

group = "lesen"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

ktlint { version.set(libs.versions.ktlintTool) }

dependencies {
    implementation(platform(libs.wasichai.bom))
    implementation(libs.wasichai.starter)
    implementation(libs.springdoc.webflux.api)
    testImplementation(libs.wasichai.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}

// unitarias y de arquitectura: sin base de datos
tasks.test { useJUnitPlatform { excludeTags("integration") } }

// integración: PostgreSQL 18 en Testcontainers (o una base externa *_test con WASICHAI_TEST_DB_*), nunca el 5432 de una
// base con datos, y el modelo aplicado con model/apply.py
val integrationTest by tasks.registering(Test::class) {
    description = "Pruebas de integración contra PostgreSQL."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

// reactivo de verdad (CLAUDE.md, regla 2): ninguna dependencia puede colar Spring MVC ni Tomcat en la aplicación
val noServletStack by tasks.registering {
    description = "Falla si Spring MVC o Tomcat llegan a runtimeClasspath."
    group = "verification"
    val artifacts =
        configurations.runtimeClasspath
            .get()
            .incoming.artifacts.resolvedArtifacts
    doLast {
        val forbidden =
            artifacts
                .get()
                .map { it.id.componentIdentifier }
                .filterIsInstance<ModuleComponentIdentifier>()
                .map { "${it.group}:${it.module}" }
                .filter { it.endsWith(":spring-webmvc") || it.endsWith(":tomcat-embed-core") }
                .sorted()
        check(forbidden.isEmpty()) { "runtimeClasspath trae una pila de servlets: $forbidden" }
    }
}

tasks.check { dependsOn(noServletStack) }

tasks.bootJar { archiveFileName.set("lesen.jar") }

// desarrollo local: bootRun toma .env (ignorado por git) como variables de entorno reales, que es como las lee Wasichai
tasks.bootRun {
    val env = file(System.getenv("LESEN_ENV_FILE")?.takeIf { it.isNotBlank() } ?: ".env")
    if (env.exists()) {
        env
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .map { it.split("=", limit = 2) }
            .filter { (_, value) -> value.isNotBlank() }
            .forEach { (key, value) -> environment(key.trim(), value.trim()) }
    }
}
