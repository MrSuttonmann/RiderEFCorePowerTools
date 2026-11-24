import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // Stay on an API level the IDE's bundled Kotlin stdlib is guaranteed to provide.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** Local Rider install: -PriderPath, then RIDER_PATH, then the default Toolbox/installer location on Windows. */
val localRider: File? = run {
    val explicit = providers.gradleProperty("riderPath").orNull?.takeIf { it.isNotBlank() }
        ?: System.getenv("RIDER_PATH")?.takeIf { it.isNotBlank() }
    val candidates = listOfNotNull(
        explicit,
        System.getenv("LOCALAPPDATA")?.let { "$it/Programs/Rider" },
    )
    candidates.map(::File).firstOrNull { File(it, "product-info.json").isFile }
}

dependencies {
    intellijPlatform {
        if (localRider != null) {
            local(localRider.absolutePath)
        } else {
            // Installer distribution for the current OS (the multi-OS maven archive is ~4.5 GB).
            rider(providers.gradleProperty("riderVersion"))
        }
        bundledPlugin("com.intellij.database")
        bundledModule("intellij.rider")
        bundledModule("intellij.rider.model.generated")
        bundledModule("intellij.rider.rdclient.dotnet")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = "com.efpt.rider"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bundled .NET tooling. The Visual Studio extension ships prebuilt, zipped console apps that do
// all database/EF Core work out of process; this plugin ships the very same binaries.
// ---------------------------------------------------------------------------------------------
val efptRepo = layout.projectDirectory.dir(providers.gradleProperty("efptRepo").get())
val efptLib = efptRepo.dir("src/GUI/lib")
val efptGui = efptRepo.dir("src/GUI/EFCorePowerTools")

val toolFiles = listOf(
    "efreveng100.exe.zip",
    "efpt100.exe.zip",
    "DacFX161.zip",
    "T4_1000.zip", "T4_1000_Split.zip",
)

// The prebuilt efreveng zips in the EF Core Power Tools repo are trimmed for Windows (non-Windows native
// runtimes such as SQLite's e_sqlite3 are deleted). Rider also runs on macOS and Linux, so the runners can
// be published from source with all runtimes: ./gradlew buildPlugin -PbuildToolsFromSource=true
val buildToolsFromSource = providers.gradleProperty("buildToolsFromSource").map { it.toBoolean() }.getOrElse(false)
val efptCore = efptRepo.dir("src/Core")
val runnerProjects = mapOf(
    "efreveng100" to "efreveng100/efreveng100.csproj",
)
val runnerZipTasks = runnerProjects.map { (name, csproj) ->
    val publishDir = layout.buildDirectory.dir("runners/$name")
    val publish = tasks.register<Exec>("publish${name.replaceFirstChar { it.uppercase() }}") {
        group = "efpt"
        description = "dotnet publish $name (all runtimes) from the EF Core Power Tools sources."
        inputs.dir(efptCore.dir(csproj.substringBefore('/')))
        outputs.dir(publishDir)
        commandLine(
            "dotnet", "publish", efptCore.file(csproj).asFile.absolutePath,
            "-c", "Release", "--no-self-contained", "-o", publishDir.get().asFile.absolutePath,
            // Upstream Release builds treat analyzer warnings as errors; irrelevant for producing the runner.
            "-p:TreatWarningsAsErrors=false", "-p:EnforceCodeStyleInBuild=false", "-p:RunAnalyzers=false",
        )
    }
    tasks.register<Zip>("zip${name.replaceFirstChar { it.uppercase() }}") {
        group = "efpt"
        from(publish.map { publishDir }) {
            // Desktop IDEs never need mobile / browser native assets (the VS build deletes these too).
            exclude("runtimes/android*/**", "runtimes/ios*/**", "runtimes/tvos*/**", "runtimes/maccatalyst*/**", "runtimes/browser*/**")
        }
        archiveFileName.set("$name.exe.zip")
        destinationDirectory.set(layout.buildDirectory.dir("runner-zips"))
    }
}

val collectTools = tasks.register<Sync>("collectTools") {
    description = "Copies the EF Core Power Tools .NET runners into build/tools."
    if (buildToolsFromSource) {
        runnerZipTasks.forEach { from(it) }
        from(efptLib) { include(toolFiles.filterNot { it.startsWith("efreveng") }) }
    } else {
        from(efptLib) { include(toolFiles) }
    }
    from(efptGui) { include("CodeTemplates1000.zip") }
    into(layout.buildDirectory.dir("tools"))
    val required = toolFiles
    val libPath = efptLib.asFile.absolutePath
    doLast {
        val missing = required.filterNot { destinationDir.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing EF Core Power Tools binaries in $libPath: $missing. Set -PefptRepo=<path to EFCorePowerTools clone>.")
        }
    }
}

// Every sandbox (buildPlugin, runIde, tests, split-mode runs) needs the bundled runners in <plugin>/tools.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    dependsOn(collectTools)
    from(collectTools) {
        into(pluginName.map { "$it/tools" })
    }
    // MIT notice for the bundled EF Core Power Tools binaries and ported code.
    from(files("THIRD-PARTY-NOTICES.md", "LICENSE")) {
        into(pluginName)
    }
}

tasks {
    wrapper {
        gradleVersion = "9.9.2"
    }
}
