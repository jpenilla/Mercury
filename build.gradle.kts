import java.util.concurrent.Callable

plugins {
    `java-library`
    `maven-publish`
    id("uk.jamierocks.propatcher") version "1.3.2"
    id("org.cadixdev.licenser") version "0.5.0"
}

val artifactId = name.toLowerCase()
base.archivesBaseName = artifactId

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

configurations {
    register("jdt") {
        isTransitive = false
    }
}

repositories {
    mavenCentral()
}

val jdt = "org.eclipse.jdt:org.eclipse.jdt.core:3.25.0"
dependencies {
    api(jdt)

    // TODO: Split in separate modules
    api("org.cadixdev:at:0.1.0-rc1")
    api("org.cadixdev:lorenz:0.5.7")

    "jdt"("$jdt:sources")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.cadixdev:lorenz-io-jam:0.5.7")
}

tasks.withType<Javadoc> {
    exclude("org.cadixdev.$artifactId.jdt.".replace('.', '/'))
}

// Patched ImportRewrite from JDT
patches {
    patches = file("patches")
    root = file("build/jdt/original")
    target = file("build/jdt/patched")
}
val jdtSrcDir = file("jdt")

val extract = task<Copy>("extractJdt") {
    dependsOn(configurations["jdt"])
    from(Callable { zipTree(configurations["jdt"].singleFile) })
    destinationDir = patches.root

    include("org/eclipse/jdt/core/dom/rewrite/ImportRewrite.java")
    include("org/eclipse/jdt/internal/core/dom/rewrite/imports/*.java")
}
tasks["applyPatches"].inputs.files(extract)

val renames = listOf(
        "org.eclipse.jdt.core.dom.rewrite" to "org.cadixdev.$artifactId.jdt.rewrite.imports",
        "org.eclipse.jdt.internal.core.dom.rewrite.imports" to "org.cadixdev.$artifactId.jdt.internal.rewrite.imports"
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
    manifest.attributes(mapOf("Automatic-Module-Name" to "org.cadixdev.$artifactId"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

val sourceJar = task<Jar>("sourceJar") {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadocJar = task<Jar>("javadocJar") {
    classifier = "javadoc"
    from(tasks["javadoc"])
}

artifacts {
    add("archives", sourceJar)
    add("archives", javadocJar)
}

license {
    header = file("HEADER")
    exclude("org.cadixdev.$artifactId.jdt.".replace('.', '/'))
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = base.archivesBaseName

            artifact(sourceJar)
            artifact(javadocJar)

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
        val maven_url: String? = System.getenv("MAVEN_URL")

        if (maven_url != null) {
            maven(url = maven_url) {
                val mavenPass: String? = System.getenv("MAVEN_PASSWORD")
                mavenPass?.let {
                    credentials {
                        username = System.getenv("MAVEN_USERNAME")
                        password = mavenPass
                    }
                }
            }
        }
    }
}

operator fun Property<String>.invoke(v: String) = set(v)
