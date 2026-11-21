plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

val modId: String = providers.gradleProperty("mod_id").get()
val modVersion: String = providers.gradleProperty("mod_version").get()
val modGroupId: String = providers.gradleProperty("mod_group_id").get()
val modName: String = providers.gradleProperty("mod_name").get()
val modLicense: String = providers.gradleProperty("mod_license").get()
val minecraftVersion: String = providers.gradleProperty("minecraft_version").get()
val minecraftVersionRange: String = providers.gradleProperty("minecraft_version_range").get()
val neoVersion: String = providers.gradleProperty("neo_version").get()

version = modVersion
group = modGroupId

base {
    archivesName.set(modId)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-snapshots/") {
        name = "opencollab-snapshots"
    }
    maven("https://repo.opencollab.dev/maven-releases/") {
        name = "opencollab-releases"
    }
}

neoForge {
    version = neoVersion

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }
        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }
        create("gameTestServer") {
            type.set("gameTestServer")
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }
        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            jvmArgument("--enable-native-access=ALL-UNNAMED")
            logLevel.set(org.slf4j.event.Level.INFO)
        }
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }

    unitTest {
        enable()
        testedMod.set(mods.getByName(modId))
    }
}

val protocolVersion = "3.0.0.Beta15-SNAPSHOT"
val netherNetVersion = "2.0.0.CR6-SNAPSHOT"
val libDataChannelVersion = "0.24.6.0-SNAPSHOT"

dependencies {
    // CloudburstMC Bedrock Protocol + codecs (v2168..v2225); reuse NeoForge Netty 4.2 & SLF4J
    implementation("org.cloudburstmc.protocol:bedrock-connection:$protocolVersion") {
        exclude(group = "io.netty")
        exclude(group = "org.slf4j")
    }

    // CloudburstMC NetherNet Netty transport (WebRTC + UDP 7551 discovery/signaling)
    implementation("org.cloudburstmc.netty:netty-transport-nethernet:$netherNetVersion") {
        exclude(group = "io.netty")
        exclude(group = "org.slf4j")
    }

    // Cross-platform libdatachannel JNI bundle (windows-x86_64, linux-glibc-x86_64, etc.)
    implementation("dev.opencollab:libdatachannel-java-arch-detect:$libDataChannelVersion") {
        exclude(group = "io.netty")
        exclude(group = "org.slf4j")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "minecraft_version_range" to minecraftVersionRange,
        "neo_version" to neoVersion,
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_license" to modLicense,
        "mod_version" to modVersion,
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}

sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
