plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "com.example.aivillager"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    // 本机访问 Maven Central 的 TLS 握手不稳定，优先走阿里云镜像；Paper 仓库保留给 dev bundle。
    maven("https://maven.aliyun.com/repository/public")
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // 统一使用 Paper 1.21.11 的 dev bundle，里面同时包含 Paper API 和 Mojang 映射的 NMS。
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

// 输出 Mojang 映射的插件，Paper 1.21.11 运行时就是 Mojang 映射，不需要再 reobf。
paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    compileJava {
        options.release = 21
        options.encoding = "UTF-8"
    }
}
