package org.tudalgo.algoutils.tutor.general;

import org.sourcegrade.jagr.api.testing.TestCycle;
import org.tudalgo.algoutils.tutor.general.io.JavaResource;
import org.tudalgo.algoutils.tutor.general.io.JavaSubmissionResource;
import org.tudalgo.algoutils.tutor.general.match.Matcher;
import org.tudalgo.algoutils.tutor.general.match.Stringifiable;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.support.compiler.VirtualFile;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.sourcegrade.jagr.api.testing.extension.TestCycleResolver.getTestCycle;

/**
 * A collection of utilities for working with spoon.
 */
public class SpoonUtils {

    private static final Pattern SPOON_NAME_PATTERN = Pattern.compile("((?!Ct|Impl)[A-Z][a-z]*)");

    /**
     * The resources to access the source code.
     */
    private static final Set<JavaResource> RESOURCES = Set.of(
        new JavaSubmissionResource()
        // https://github.com/INRIA/spoon/issues/5290
        //new JavaStdlibResource()
    );

    private SpoonUtils() {
    }

    /**
     * @deprecated use {@link #getType(String)} instead
     */
    public static <T, U extends CtType<?>> T getCtElementForSourceCode(
        String ignoredSourceCode,
        Class<U> ignoredKind,
        Matcher<Stringifiable> ignoredNameMatcher
    ) {
        throw new UnsupportedOperationException("use getType instead");
    }

    /**
     * Returns the ct type from the spoon model which matches the given predicate.
     *
     * @param className the qualified name of the class to add to the model
     * @param <T>       the type of the ct type
     * @return the ct type from the spoon model which matches the given predicate
     * @throws NoSuchElementException if no element matches the given predicate
     */
    public static <T extends CtType<?>> T getType(String className) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);

        String sourceCode = RESOURCES.stream()
            .filter(resource -> resource.contains(className))
            .findFirst()
            .orElseThrow()
            .getContent(className);

        VirtualFile file = new VirtualFile(sourceCode, className);

        @SuppressWarnings("UnstableApiUsage") TestCycle cycle = getTestCycle();
        if (cycle != null) {
            launcher.getEnvironment().setInputClassLoader((ClassLoader) cycle.getClassLoader());
        }

        launcher.addInputResource(file);
        CtModel model = launcher.buildModel();
        //noinspection unchecked
        return (T) model.getAllTypes().stream().filter(it -> it.getQualifiedName().equals(className)).findFirst().orElseThrow();
    }

    /**
     * <p>Returns a human-readable name of the given element.</p>
     *
     * @param element the element
     * @return the human-readable name
     */
    public static String getNameOfCtElement(CtElement element) {
        return getNameOfCtElement(element.getClass());
    }

    /**
     * <p>Returns a human-readable name of the given element type.</p>
     *
     * @param type the element type
     * @return the human-readable name
     */
    public static String getNameOfCtElement(Class<?> type) {
        var name = type.getSimpleName();
        var match = SPOON_NAME_PATTERN.matcher(name);
        name = match.results().map(m -> m.group(1).toLowerCase()).collect(Collectors.joining(" "));
        return name;
    }

    /**
     * @deprecated use {@link #getType(String)} instead
     */
    public static CtModel getCtModel() {
        throw new UnsupportedOperationException("use getType instead");
    }
}
