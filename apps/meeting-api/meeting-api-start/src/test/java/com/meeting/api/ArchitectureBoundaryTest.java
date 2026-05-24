package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    /**
     * Tenant-scope guard: every production Facade implementation that
     * touches tenant-owned data must inject {@link TenantScopedTransaction}.
     * Without it, the bean cannot set {@code app.tenant_id} before
     * running SQL, and {@code FORCE ROW LEVEL SECURITY} policies return
     * empty result sets — silently breaking reads.
     *
     * <p>Scoped to {@code com.meeting.api.app..} so test-local stub
     * implementations (e.g. {@code MeetingControllerTest$StubMeetingFacade})
     * are not flagged. {@code AuthFacade} implementations are excluded
     * because login/logout/authenticate operate on session state, not
     * tenant-owned tables.
     */
    @Test
    void facadeImplementationsMustDependOnTenantScopedTransaction() {
        DescribedPredicate<JavaClass> tenantScopedFacadeImpl = new DescribedPredicate<>(
            "production Facade implementations under com.meeting.api.app.. (excluding AuthFacade)"
        ) {
            @Override
            public boolean test(JavaClass clazz) {
                if (clazz.isInterface()) return false;
                if (!clazz.getPackageName().startsWith("com.meeting.api.app")) return false;
                return clazz.getAllRawInterfaces().stream().anyMatch(iface ->
                    iface.getPackageName().startsWith("com.meeting.api.client")
                        && iface.getSimpleName().endsWith("Facade")
                        && !iface.getSimpleName().equals("AuthFacade")
                );
            }
        };

        classes()
            .that(tenantScopedFacadeImpl)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(TenantScopedTransaction.class)
            .check(importedClasses);
    }
}
