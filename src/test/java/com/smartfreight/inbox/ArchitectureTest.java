package com.smartfreight.inbox;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules that hold for the code as it is today, so a refactoring cannot quietly
 * break them. Where today's code has known exceptions they are listed by name: the list may
 * only shrink — a new exception means the change is going the wrong way.
 *
 * Package cycles (service ↔ config / webhook / startup, entity ↔ messaging) are known and are
 * not checked here; removing them is part of the package-by-feature step in docs/refactoring.md.
 */
class ArchitectureTest {

    private static final String ROOT = "com.smartfreight.inbox";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /** Controllers that still use a repository directly instead of a service. */
    private static final Set<String> CONTROLLERS_USING_REPOSITORIES = Set.of(
            "AdminController",
            "DocumentApiController",
            "RateApiController",
            "TicketApiController",
            "TrainingExampleApiController");

    /**
     * Entities without {@code @TenantId}, each on purpose: the tenant table itself, the
     * platform-wide default settings (per-tenant overrides live in {@code TenantSetting}), and
     * the outbox, which is also written from system context where Hibernate has no tenant to
     * stamp — its publisher sets {@code tenant_id} explicitly and RLS still scopes the table.
     */
    private static final Set<String> NOT_TENANT_ID_ENTITIES = Set.of("Tenant", "AppSetting", "OutboxEvent");

    private static String pkg(String name) {
        return ROOT + "." + name + "..";
    }

    @Test
    void repositoriesDependOnlyOnEntities() {
        noClasses().that().resideInAPackage(pkg("repository"))
                .should().dependOnClassesThat(applicationClassOutside("repository", "entity"))
                .check(CLASSES);
    }

    @Test
    void nothingOutsideControllersDependsOnControllers() {
        noClasses().that().resideOutsideOfPackage(pkg("controller"))
                .should().dependOnClassesThat().resideInAPackage(pkg("controller"))
                .check(CLASSES);
    }

    @Test
    void servicesDoNotDependOnEntryPoints() {
        noClasses().that().resideInAPackage(pkg("service"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        pkg("controller"), pkg("messaging"), pkg("integration"), pkg("scheduler"))
                .check(CLASSES);
    }

    @Test
    void leafPackagesDependOnNoOtherApplicationPackage() {
        for (String leaf : new String[] {"tenancy", "util", "exception"}) {
            noClasses().that().resideInAPackage(pkg(leaf))
                    .should().dependOnClassesThat(applicationClassOutside(leaf))
                    .check(CLASSES);
        }
    }

    @Test
    void entitiesDependOnlyOnEntitiesAndTheLiveEventListener() {
        noClasses().that().resideInAPackage(pkg("entity"))
                .should().dependOnClassesThat(applicationClassOutside("entity")
                        .and(DescribedPredicate.not(JavaClass.Predicates.simpleName("TicketLiveEventListener"))))
                .check(CLASSES);
    }

    @Test
    void onlyKnownControllersUseRepositories() {
        noClasses().that().resideInAPackage(pkg("controller"))
                .and(DescribedPredicate.not(simpleNameIn(CONTROLLERS_USING_REPOSITORIES)))
                .should().dependOnClassesThat().resideInAPackage(pkg("repository"))
                .check(CLASSES);
    }

    @Test
    void everyEntityIsTenantScoped() {
        classes().that().areAnnotatedWith(Entity.class)
                .and(DescribedPredicate.not(simpleNameIn(NOT_TENANT_ID_ENTITIES)))
                .should(haveTenantIdField())
                .check(CLASSES);
    }

    // ---- helpers ----

    /** An application class (not a library) that lives outside all of the given packages. */
    private static DescribedPredicate<JavaClass> applicationClassOutside(String... allowed) {
        return DescribedPredicate.describe("application classes outside " + String.join("/", allowed),
                c -> isApplicationPackage(c.getPackageName())
                        && Arrays.stream(allowed).noneMatch(a -> isInPackage(c.getPackageName(), ROOT + "." + a)));
    }

    /** The root package itself counts (InboxApplication lives there); a look-alike such as com.smartfreight.inboxx does not. */
    private static boolean isApplicationPackage(String pkg) {
        return isInPackage(pkg, ROOT);
    }

    /** {@code pkg} is {@code base} or one of its sub-packages — "entity" does not also match "entityx". */
    private static boolean isInPackage(String pkg, String base) {
        return pkg.equals(base) || pkg.startsWith(base + ".");
    }

    private static DescribedPredicate<JavaClass> simpleNameIn(Set<String> names) {
        return DescribedPredicate.describe("simple name in " + names, c -> names.contains(c.getSimpleName()));
    }

    private static ArchCondition<JavaClass> haveTenantIdField() {
        return new ArchCondition<>("have a field annotated with @TenantId") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean scoped = item.getAllFields().stream().anyMatch(f -> f.isAnnotatedWith(TenantId.class));
                events.add(new SimpleConditionEvent(item, scoped,
                        item.getSimpleName() + (scoped ? " is" : " is not") + " tenant-scoped (@TenantId)"));
            }
        };
    }
}
