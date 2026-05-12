package com.meeting.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture boundary tests for COLA-V5 layered architecture.
 *
 * Rules:
 * 1. Domain must not depend on Spring Web, JDBC, MQ, or any infrastructure SDK.
 * 2. App may depend on domain and client, but not on infrastructure directly.
 * 3. Client is the inner-most layer; nothing should depend on adapter or infrastructure.
 * 4. Infrastructure may depend on app and domain (outer layer).
 * 5. Adapter may depend on app and client (presentation layer).
 */
class ArchitectureBoundaryTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
            .importPackages("com.meeting.api");
    }

    @Test
    void domainShouldNotDependOnSpringWeb() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
            .check(importedClasses);
    }

    @Test
    void domainShouldNotDependOnSpringDataJdbc() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.data..",
                "org.springframework.jdbc..",
                "java.sql..",
                "javax.sql.."
            )
            .check(importedClasses);
    }

    @Test
    void domainShouldNotDependOnMessageQueueSdk() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.amqp..",
                "com.rabbitmq..",
                "org.apache.kafka..",
                "software.amazon.awssdk.."
            )
            .check(importedClasses);
    }

    @Test
    void domainShouldNotDependOnInfrastructure() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .check(importedClasses);
    }

    @Test
    void appShouldNotDependOnInfrastructure() {
        noClasses()
            .that()
            .resideInAPackage("..app..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .check(importedClasses);
    }

    @Test
    void appShouldNotDependOnAdapter() {
        noClasses()
            .that()
            .resideInAPackage("..app..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .check(importedClasses);
    }

    @Test
    void clientShouldNotDependOnAnyOuterLayer() {
        noClasses()
            .that()
            .resideInAPackage("..client..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter..", "..infrastructure..", "..app..")
            .check(importedClasses);
    }

    @Test
    void noCyclicDependenciesBetweenModules() {
        SlicesRuleDefinition.slices()
            .matching("com.meeting.api.(*)..")
            .should().beFreeOfCycles()
            .check(importedClasses);
    }
}
