package org.tudalgo.algoutils.tutor.general.io.parser;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Parses a Java source code file as String and extracts information about the Java file.
 *
 * @author Nhan Huynh
 */
public class JavaSourceCodeStringParser implements JavaSourceCodeParser {

    /**
     * The source code of the Java file.
     */
    private final String sourceCode;

    /**
     * The current character index of the source code.
     */
    private int current = 0;

    /**
     * The package name of the Java file.
     */
    private @Nullable String packageName;

    /**
     * The class name of the Java file.
     */
    private @Nullable String className;

    /**
     * Constructs a new {@link JavaSourceCodeStringParser} with the specified source code to be parsed.
     *
     * @param sourceCode the source code of the Java file
     */
    public JavaSourceCodeStringParser(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    /**
     * Skips all comments and moves the current index to the next non-comment character.
     */
    private void skipComment() {
        int size = sourceCode.length();
        for (; current < size; current++) {
            if (Character.isWhitespace(sourceCode.charAt(current))) {
                continue;
            }
            if (sourceCode.startsWith(COMMENT_SINGLE_LINE, current)) {
                current += COMMENT_SINGLE_LINE.length();
            } else if (sourceCode.startsWith(COMMENT_MULTI_LINE_START, current)) {
                current += COMMENT_MULTI_LINE_START.length();
                for (; current < size; current++) {
                    if (sourceCode.startsWith(COMMENT_MULTI_LINE_END, current)) {
                        // -1 since we increment it in the loop already
                        current += COMMENT_MULTI_LINE_END.length() - 1;
                        break;
                    }
                }
            } else if (sourceCode.startsWith(JAVA_DOC_START, current)) {
                current += JAVA_DOC_START.length();
                for (; current < size; current++) {
                    if (sourceCode.startsWith(JAVA_DOC_END, current)) {
                        // -1 since we increment it in the loop already
                        current += JAVA_DOC_END.length() - 1;
                        break;
                    }
                }
            } else {
                break;
            }
        }
    }

    /**
     * Visits the package name of the Java file.
     */
    private void visitPackageName() {
        skipComment();
        List<String> classKeywords = JavaSourceCodeParser.classKeywords();
        int size = sourceCode.length();
        for (; current < size; current++) {
            // Default package
            if (classKeywords.stream().anyMatch(keyword -> sourceCode.startsWith(keyword, current))) {
                packageName = "";
                break;
            }
            if (!sourceCode.startsWith(PACKAGE_KEYWORD, current)) {
                continue;
            }

            // Valid package name
            current += PACKAGE_KEYWORD.length();
            skipComment();

            // Retrieve package name
            for (StringBuilder builder = new StringBuilder(); current < size; current++) {
                char token = sourceCode.charAt(current);
                if (!JavaSourceCodeParser.isPackageNameToken(token)) {
                    packageName = builder.toString();
                    return;
                }
                builder.append(token);
            }
        }
    }

    @Override
    public String getPackageName() {
        if (packageName != null) return packageName;
        visitPackageName();
        return packageName;
    }

    /**
     * Visits the class name of the Java file.
     */
    private void visitClassName() {
        skipComment();
        int size = sourceCode.length();
        List<String> classKeywords = JavaSourceCodeParser.classKeywords();
        for (; current < size; current++) {
            skipComment();

            if (classKeywords.stream().noneMatch(keyword -> sourceCode.startsWith(keyword, current))) {
                continue;
            }

            String keyword = classKeywords.stream()
                .filter(k -> sourceCode.startsWith(k, current))
                .findFirst()
                .orElseThrow();

            // Valid class name
            current += keyword.length();
            skipComment();
            for (StringBuilder builder = new StringBuilder(); current < size; current++) {
                char token = sourceCode.charAt(current);
                if (!JavaSourceCodeParser.isClassNameToken(token)) {
                    className = builder.toString();
                    return;
                }
                builder.append(token);
            }
        }
    }

    @Override
    public String getClassName() {
        if (className != null) return className;
        getPackageName();
        visitClassName();
        return className;
    }

    @Override
    public void close() {
        // Nothing to do
    }
}
