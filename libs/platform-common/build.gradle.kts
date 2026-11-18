plugins {
    `java-library`
    id("io.spring.dependency-management")
}

dependencies {
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    compileOnly("org.springframework.boot:spring-boot-starter-cache")
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")
    compileOnly("com.github.ben-manes.caffeine:caffeine")
    compileOnly("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-cache")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("com.github.ben-manes.caffeine:caffeine")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
