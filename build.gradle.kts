plugins {
    `java-library`
    signing
    `maven-publish`
    id("uk.jamierocks.propatcher") version "2.0.1"
    id("org.cadixdev.licenser") version "0.6.1"
}

val artifactId = name.toLowerCase()
base.archivesName.set(artifactId)

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

val jdt: Configuration by configurations.creating {
    isTransitive = false
}

repositories {
    mavenCentral()
}

val jdtCoordinates = "org.eclipse.jdt:org.eclipse.jdt.core:3.27.0"
dependencies {
    api(jdtCoordinates)

    // TODO: Split in separate modules
    api("org.cadixdev:at:0.1.0-rc1")
    api("org.cadixdev:lorenz:0.5.8")

    jdt("$jdtCoordinates:sources")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.cadixdev:lorenz-io-jam:0.5.8")
}

tasks.withType<Javadoc> {
    exclude("${project.group}.$artifactId.jdt.".replace('.', '/'))
}

// Patched ImportRewrite from JDT
patches {
    patches = file("patches")
    rootDir = file("build/jdt/original")
    target = file("build/jdt/patched").also { it.mkdirs() }
}
val jdtSrcDir = file("jdt")

val extractJdt = task<Copy>("extractJdt") {
    from(jdt.elements.map { zipTree(it.single()) })
    destinationDir = patches.rootDir

    include("org/eclipse/jdt/core/dom/rewrite/ImportRewrite.java")
    include("org/eclipse/jdt/internal/core/dom/rewrite/imports/*.java")
}
tasks.applyPatches {
    inputs.files(extractJdt)
}
tasks.resetSources {
    dependsOn(extractJdt)
}

val renames = listOf(
        "org.eclipse.jdt.core.dom.rewrite" to "$group.$artifactId.jdt.rewrite.imports",
        "org.eclipse.jdt.internal.core.dom.rewrite.imports" to "$group.$artifactId.jdt.internal.rewrite.imports"
)

fun createRenameTask(prefix: String, inputDir: File, outputDir: File, renames: List<Pair<String, String>>): Task
        = task<Copy>("${prefix}renameJdt") {
    destinationDir = file(outputDir)

    renames.forEach { (old, new) ->
        from("$inputDir/${old.replace('.', '/')}") {
            into("${new.replace('.', '/')}/")
        }
    }

    filter { renames.fold(it) { s, (from, to) -> s.replace(from, to) } }
}

val renameTask = createRenameTask("", patches.target, jdtSrcDir, renames)
renameTask.inputs.files(tasks["applyPatches"])

tasks["makePatches"].inputs.files(createRenameTask("un", jdtSrcDir, patches.target, renames.map { (a,b) -> b to a }))
sourceSets["main"].java.srcDirs(renameTask)

tasks.jar.configure {
    manifest.attributes(mapOf("Automatic-Module-Name" to "${project.group}.$artifactId"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

license {
    header.set(resources.text.fromFile(file("HEADER")))
    exclude("$group.$artifactId.jdt.".replace('.', '/'))
}

val isSnapshot = version.toString().endsWith("-SNAPSHOT")

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = base.archivesName.get()

            pom {
                val name: String by project
                val description: String by project
                val url: String by project
                name(name)
                description(description)
                url(url)

                scm {
                    url(url)
                    connection("scm:git:$url.git")
                    developerConnection.set(connection)
                }

                issueManagement {
                    system("GitHub Issues")
                    url("$url/issues")
                }

                licenses {
                    license {
                        name("Eclipse Public License, Version 2.0")
                        url("https://www.eclipse.org/legal/epl-2.0/")
                        distribution("repo")
                    }
                }

                developers {
                    developer {
                        id("jamierocks")
                        name("Jamie Mansfield")
                        email("jmansfield@cadixdev.org")
                        url("https://www.jamiemansfield.me/")
                        timezone("Europe/London")
                    }
                }
            }
        }
    }

    repositories {
        val sonatypeUsername: String? by project
        val sonatypePassword: String? by project
        if (sonatypeUsername != null && sonatypePassword != null) {
            val url = if (isSnapshot) "https://oss.sonatype.org/content/repositories/snapshots/"
                else "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
            maven(url) {
                credentials {
                    username = sonatypeUsername
                    password = sonatypePassword
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

tasks.withType<Sign> {
    onlyIf { !isSnapshot }
}

operator fun Property<String>.invoke(v: String) = set(v)
