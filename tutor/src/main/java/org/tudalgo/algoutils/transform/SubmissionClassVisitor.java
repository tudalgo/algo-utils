package org.tudalgo.algoutils.transform;

import org.tudalgo.algoutils.transform.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;
import java.util.stream.Collectors;

import static org.tudalgo.algoutils.transform.util.TransformationUtils.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A class visitor merging a submission class with its corresponding solution class, should one exist.
 * The heart piece of the {@link SolutionMergingClassTransformer} processing chain.
 * <br>
 * Main features:
 * <ul>
 *     <li>
 *         <b>Method invocation logging</b><br>
 *         Logs the parameter values the method was called with.
 *         This allows the user to verify that a method was called and also that it was called with
 *         the right parameters.
 *         If the target method is not static or a constructor, the object the method was invoked on
 *         is logged as well.
 *     </li>
 *     <li>
 *         <b>Method substitution</b><br>
 *         Allows for "replacement" of a method at runtime.
 *         While the method itself must still be invoked, it will hand over execution to the provided
 *         substitution.
 *         This can be useful when a method should always return a certain value, regardless of object state
 *         or for making a non-deterministic method (e.g., RNG) return deterministic values.
 *         Replacing constructors is currently not supported.
 *         Can be combined with invocation logging.
 *     </li>
 *     <li>
 *         <b>Method delegation</b><br>
 *         Will effectively "replace" the code of the original submission with the one from the solution.
 *         While the instructions from both submission and solution are present in the merged method, only
 *         one can be active at a time.
 *         This allows for improved unit testing by not relying on submission code transitively.
 *         If this mechanism is used and no solution class is associated with this submission class or
 *         the solution class does not contain a matching method, the submission code will be used
 *         as a fallback.
 *         Can be combined with invocation logging.
 *     </li>
 * </ul>
 * All of these options can be enabled / disabled via {@link SubmissionExecutionHandler}.
 *
 * @see SubmissionExecutionHandler
 * @author Daniel Mangold
 */
class SubmissionClassVisitor extends ClassVisitor {

    private final boolean defaultTransformationsOnly;
    private final TransformationContext transformationContext;
    private final String className;
    private final SubmissionClassInfo submissionClassInfo;

    private final Set<FieldHeader> visitedFields = new HashSet<>();
    private final Map<FieldHeader, FieldNode> solutionFieldNodes;

    private final Set<MethodHeader> visitedMethods = new HashSet<>();
    private final Map<MethodHeader, MethodNode> solutionMethodNodes;

    SubmissionClassVisitor(ClassVisitor classVisitor,
                           TransformationContext transformationContext,
                           String submissionClassName) {
        super(ASM9, classVisitor);
        this.transformationContext = transformationContext;
        this.className = transformationContext.getSubmissionClassInfo(submissionClassName).getComputedClassName();
        this.submissionClassInfo = transformationContext.getSubmissionClassInfo(submissionClassName);

        Optional<SolutionClassNode> solutionClass = submissionClassInfo.getSolutionClass();
        if (solutionClass.isPresent()) {
            this.defaultTransformationsOnly = false;
            this.solutionFieldNodes = solutionClass.get().getFields();
            this.solutionMethodNodes = solutionClass.get().getMethods();
        } else {
            System.err.printf("No corresponding solution class found for %s. Only applying default transformations.%n", submissionClassName);
            this.defaultTransformationsOnly = true;
            this.solutionFieldNodes = Collections.emptyMap();
            this.solutionMethodNodes = Collections.emptyMap();
        }
    }

    /**
     * Visits the header of the class, replacing it with the solution class' header, if one is present.
     */
    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        submissionClassInfo.getSolutionClass()
            .map(SolutionClassNode::getClassHeader)
            .orElse(submissionClassInfo.getOriginalClassHeader())
            .visitClass(getDelegate(), version);
//            .visitClass(getDelegate(), version, Type.getInternalName(SubmissionClassMetadata.class));
    }

    /**
     * Visits a field of the submission class and transforms it if a solution class is present.
     */
    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if (defaultTransformationsOnly) {
            return super.visitField(access, name, descriptor, signature, value);
        }

        FieldHeader fieldHeader = submissionClassInfo.getComputedFieldHeader(name);
        visitedFields.add(fieldHeader);
        return fieldHeader.toFieldVisitor(getDelegate(), value);
    }

    /**
     * Visits a method of a submission class and transforms it.
     * Enables invocation logging, substitution and, if a solution class is present, delegation.
     */
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodHeader methodHeader = submissionClassInfo.getComputedMethodHeader(name, descriptor);
        visitedMethods.add(methodHeader);
        boolean isStatic = (methodHeader.access() & ACC_STATIC) != 0;
        boolean isConstructor = methodHeader.name().equals("<init>");

        // calculate length of locals array, including "this" if applicable
        int submissionExecutionHandlerIndex = (Type.getArgumentsAndReturnSizes(methodHeader.descriptor()) >> 2) - (isStatic ? 1 : 0);
        int methodHeaderIndex = submissionExecutionHandlerIndex + 1;

        // calculate default locals for frames
        List<Object> parameterTypes = Arrays.stream(Type.getArgumentTypes(methodHeader.descriptor()))
            .map(type -> switch (type.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> INTEGER;
                case Type.FLOAT -> FLOAT;
                case Type.LONG -> LONG;
                case Type.DOUBLE -> DOUBLE;
                default -> type.getInternalName();
            })
            .collect(Collectors.toList());
        if (!isStatic) {
            parameterTypes.addFirst(isConstructor ? UNINITIALIZED_THIS : className);
        }
        Object[] fullFrameLocals = parameterTypes.toArray();

        return new MethodVisitor(ASM9, methodHeader.toMethodVisitor(getDelegate())) {
            @Override
            public void visitCode() {
                // if method is abstract or lambda, skip transformation
                if ((methodHeader.access() & ACC_ABSTRACT) != 0 ||
                    ((methodHeader.access() & ACC_SYNTHETIC) != 0 && methodHeader.name().startsWith("lambda$"))) {
                    super.visitCode();
                    return;
                }

                Label submissionExecutionHandlerVarLabel = new Label();
                Label methodHeaderVarLabel = new Label();
                Label substitutionCheckLabel = new Label();
                Label delegationCheckLabel = new Label();
                Label delegationCodeLabel = new Label();
                Label submissionCodeLabel = new Label();

                // create SubmissionExecutionHandler$Internal instance and store in locals array
                super.visitTypeInsn(NEW, SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName());
                super.visitInsn(DUP);
                super.visitMethodInsn(INVOKESTATIC,
                    SubmissionExecutionHandler.INTERNAL_TYPE.getInternalName(),
                    "getInstance",
                    Type.getMethodDescriptor(SubmissionExecutionHandler.INTERNAL_TYPE),
                    false);
                super.visitMethodInsn(INVOKESPECIAL,
                    SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName(),
                    "<init>",
                    Type.getMethodDescriptor(Type.VOID_TYPE, SubmissionExecutionHandler.INTERNAL_TYPE),
                    false);
                super.visitVarInsn(ASTORE, submissionExecutionHandlerIndex);
                super.visitLabel(submissionExecutionHandlerVarLabel);

                // replicate method header in bytecode and store in locals array
                buildMethodHeader(getDelegate(), methodHeader);
                super.visitVarInsn(ASTORE, methodHeaderIndex);
                super.visitLabel(methodHeaderVarLabel);

                super.visitFrame(F_APPEND,
                    2,
                    new Object[] {SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName(), methodHeader.getType().getInternalName()},
                    0,
                    null);

                // check if invocation should be logged
                super.visitVarInsn(ALOAD, submissionExecutionHandlerIndex);
                super.visitVarInsn(ALOAD, methodHeaderIndex);
                super.visitMethodInsn(INVOKEVIRTUAL,
                    SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName(),
                    "logInvocation",
                    Type.getMethodDescriptor(Type.BOOLEAN_TYPE, methodHeader.getType()),
                    false);
                super.visitJumpInsn(IFEQ, isConstructor ? // jump to label if logInvocation(...) == false
                    defaultTransformationsOnly ? submissionCodeLabel : delegationCheckLabel :
                    substitutionCheckLabel);

                // intercept parameters
                super.visitVarInsn(ALOAD, submissionExecutionHandlerIndex);
                super.visitVarInsn(ALOAD, methodHeaderIndex);
                buildInvocation(Type.getArgumentTypes(methodHeader.descriptor()));
                super.visitMethodInsn(INVOKEVIRTUAL,
                    SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName(),
                    "addInvocation",
                    Type.getMethodDescriptor(Type.VOID_TYPE,
                        methodHeader.getType(),
                        Invocation.INTERNAL_TYPE),
                    false);

                // check if substitution exists for this method if not constructor (because waaay too complex right now)
                if (!isConstructor) {
                    super.visitFrame(F_SAME, 0, null, 0, null);
                    super.visitLabel(substitutionCheckLabel);
                    super.visitVarInsn(ALOAD, submissionExecutionHandlerIndex);
                    super.visitVarInsn(ALOAD, methodHeaderIndex);
                    super.visitMethodInsn(INVOKEVIRTUAL,
                        SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName(),
                        "useSubstitution",
                        Type.getMethodDescriptor(Type.BOOLEAN_TYPE, methodHeader.getType()),
                        false);
                    super.visitJumpInsn(IFEQ, defaultTransformationsOnly ? submissionCodeLabel : delegationCheckLabel); // jump to label if useSubstitution(...) == false

                    // get substitution and execute it
                    super.visitVarInsn(ALOAD, submissionExecutionHandlerIndex);
                    super.visitVarInsn(ALOAD, methodHeaderIndex);
                    super.visitMethodInsn(INVOKEVIRTUAL,
                        SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName(),
                        "getSubstitution",
                        Type.getMethodDescriptor(Invocation.INTERNAL_TYPE,
                            methodHeader.getType()),
                        false);
                    buildInvocation(Type.getArgumentTypes(methodHeader.descriptor()));
                    super.visitMethodInsn(INVOKEINTERFACE,
                        SubmissionExecutionHandler.MethodSubstitution.INTERNAL_TYPE.getInternalName(),
                        "execute",
                        Type.getMethodDescriptor(Type.getType(Object.class),
                            Invocation.INTERNAL_TYPE),
                        true);
                    Type returnType = Type.getReturnType(methodHeader.descriptor());
                    if (returnType.getSort() == Type.ARRAY || returnType.getSort() == Type.OBJECT) {
                        super.visitTypeInsn(CHECKCAST, returnType.getInternalName());
                    } else {
                        unboxType(getDelegate(), returnType);
                    }
                    super.visitInsn(returnType.getOpcode(IRETURN));
                }

                // if only default transformations are applied, skip delegation
                if (!defaultTransformationsOnly) {
                    // check if call should be delegated to solution or not
                    super.visitFrame(F_SAME, 0, null, 0, null);
                    super.visitLabel(delegationCheckLabel);
                    super.visitVarInsn(ALOAD, submissionExecutionHandlerIndex);
                    super.visitVarInsn(ALOAD, methodHeaderIndex);
                    super.visitMethodInsn(INVOKEVIRTUAL,
                        SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getInternalName(),
                        "useSubmissionImpl",
                        Type.getMethodDescriptor(Type.BOOLEAN_TYPE, methodHeader.getType()),
                        false);
                    super.visitJumpInsn(IFNE, submissionCodeLabel); // jump to label if useSubmissionImpl(...) == true

                    // replay instructions from solution
                    super.visitFrame(F_CHOP, 2, null, 0, null);
                    super.visitLabel(delegationCodeLabel);
                    super.visitLocalVariable("submissionExecutionHandler",
                        SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getDescriptor(),
                        null,
                        submissionExecutionHandlerVarLabel,
                        delegationCodeLabel,
                        submissionExecutionHandlerIndex);
                    super.visitLocalVariable("methodHeader",
                        methodHeader.getType().getDescriptor(),
                        null,
                        methodHeaderVarLabel,
                        delegationCodeLabel,
                        methodHeaderIndex);
                    solutionMethodNodes.get(methodHeader).accept(getDelegate());

                    super.visitFrame(F_FULL, fullFrameLocals.length, fullFrameLocals, 0, new Object[0]);
                    super.visitLabel(submissionCodeLabel);
                } else {
                    super.visitFrame(F_CHOP, 2, null, 0, null);
                    super.visitLabel(submissionCodeLabel);
                    super.visitLocalVariable("submissionExecutionHandler",
                        SubmissionExecutionHandler.Internal.INTERNAL_TYPE.getDescriptor(),
                        null,
                        submissionExecutionHandlerVarLabel,
                        submissionCodeLabel,
                        submissionExecutionHandlerIndex);
                    super.visitLocalVariable("methodHeader",
                        methodHeader.getType().getDescriptor(),
                        null,
                        methodHeaderVarLabel,
                        submissionCodeLabel,
                        methodHeaderIndex);
                }

                // visit original code
                super.visitCode();
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                // skip transformation if only default transformations are applied or owner is not part of the submission
                if (defaultTransformationsOnly || !owner.startsWith(transformationContext.projectPrefix())) {
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                } else {
                    FieldHeader fieldHeader = transformationContext.getSubmissionClassInfo(owner).getComputedFieldHeader(name);
                    super.visitFieldInsn(opcode, fieldHeader.owner(), fieldHeader.name(), fieldHeader.descriptor());
                }
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                // skip transformation if only default transformations are applied or owner is not part of the submission
                if (defaultTransformationsOnly || !owner.startsWith(transformationContext.projectPrefix())) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                } else {
                    MethodHeader methodHeader = transformationContext.getSubmissionClassInfo(owner).getComputedMethodHeader(name, descriptor);
                    super.visitMethodInsn(opcode, methodHeader.owner(), methodHeader.name(), methodHeader.descriptor(), isInterface);
                }
            }

            /**
             * Builds an {@link Invocation} in bytecode.
             *
             * @param argumentTypes an array of parameter types
             */
            private void buildInvocation(Type[] argumentTypes) {
                super.visitTypeInsn(NEW, Invocation.INTERNAL_TYPE.getInternalName());
                super.visitInsn(DUP);
                if (!isStatic && !isConstructor) {
                    super.visitVarInsn(ALOAD, 0);
                    super.visitMethodInsn(INVOKESPECIAL,
                        Invocation.INTERNAL_TYPE.getInternalName(),
                        "<init>",
                        "(Ljava/lang/Object;)V",
                        false);
                } else {
                    super.visitMethodInsn(INVOKESPECIAL,
                        Invocation.INTERNAL_TYPE.getInternalName(),
                        "<init>",
                        "()V",
                        false);
                }
                for (int i = 0; i < argumentTypes.length; i++) {
                    super.visitInsn(DUP);
                    // load parameter with opcode (ALOAD, ILOAD, etc.) for type and ignore "this", if it exists
                    super.visitVarInsn(argumentTypes[i].getOpcode(ILOAD), getLocalsIndex(argumentTypes, i) + (isStatic ? 0 : 1));
                    boxType(getDelegate(), argumentTypes[i]);
                    super.visitMethodInsn(INVOKEVIRTUAL,
                        Invocation.INTERNAL_TYPE.getInternalName(),
                        "addParameter",
                        "(Ljava/lang/Object;)V",
                        false);
                }
            }
        };
    }

    /**
     * Adds all remaining fields and methods from the solution class that have not already
     * been visited (e.g., lambdas).
     */
    @Override
    public void visitEnd() {
        if (!defaultTransformationsOnly) {
            // add missing fields
            solutionFieldNodes.entrySet()
                .stream()
                .filter(entry -> !visitedFields.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .forEach(fieldNode -> fieldNode.accept(getDelegate()));
            // add missing methods (including lambdas)
            solutionMethodNodes.entrySet()
                .stream()
                .filter(entry -> !visitedMethods.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .forEach(methodNode -> methodNode.accept(getDelegate()));
        }

        classMetadata();
        fieldMetadata();
        methodMetadata();

        super.visitEnd();
    }

    private void classMetadata() {
        ClassHeader classHeader = submissionClassInfo.getOriginalClassHeader();
        Label startLabel = new Label();
        Label endLabel = new Label();
        MethodVisitor mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC,
            "getOriginalClassHeader",
            Type.getMethodDescriptor(classHeader.getType()),
            null,
            null);

        mv.visitLabel(startLabel);
        int maxStack = buildClassHeader(mv, classHeader);
        mv.visitInsn(ARETURN);
        mv.visitLabel(endLabel);
        mv.visitLocalVariable("this",
            Type.getObjectType(className).getDescriptor(),
            null,
            startLabel,
            endLabel,
            0);
        mv.visitMaxs(maxStack, 1);
    }

    private void fieldMetadata() {
        Set<FieldHeader> fieldHeaders = submissionClassInfo.getOriginalFieldHeaders();
        Label startLabel = new Label();
        Label endLabel = new Label();
        int maxStack, stackSize;
        Type setType = Type.getType(Set.class);
        MethodVisitor mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC,
            "getOriginalFieldHeaders",
            Type.getMethodDescriptor(setType),
            "()L%s<%s>;".formatted(setType.getInternalName(), Type.getDescriptor(FieldHeader.class)),
            null);

        mv.visitLabel(startLabel);
        mv.visitIntInsn(SIPUSH, fieldHeaders.size());
        mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(Object.class));
        maxStack = stackSize = 1;
        int i = 0;
        for (FieldHeader fieldHeader : fieldHeaders) {
            mv.visitInsn(DUP);
            maxStack = Math.max(maxStack, ++stackSize);
            mv.visitIntInsn(SIPUSH, i++);
            maxStack = Math.max(maxStack, ++stackSize);
            int stackSizeUsed = buildFieldHeader(mv, fieldHeader);
            maxStack = Math.max(maxStack, stackSize++ + stackSizeUsed);
            mv.visitInsn(AASTORE);
            stackSize -= 3;
        }
        mv.visitMethodInsn(INVOKESTATIC,
            setType.getInternalName(),
            "of",
            Type.getMethodDescriptor(setType, Type.getType(Object[].class)),
            true);
        mv.visitInsn(ARETURN);
        mv.visitLabel(endLabel);
        mv.visitLocalVariable("this",
            Type.getObjectType(className).getDescriptor(),
            null,
            startLabel,
            endLabel,
            0);
        mv.visitMaxs(maxStack, 1);
    }

    private void methodMetadata() {
        Set<MethodHeader> methodHeaders = submissionClassInfo.getOriginalMethodHeaders();
        Label startLabel = new Label();
        Label endLabel = new Label();
        int maxStack, stackSize;
        Type setType = Type.getType(Set.class);
        MethodVisitor mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC,
            "getOriginalMethodHeaders",
            Type.getMethodDescriptor(setType),
            "()L%s<%s>;".formatted(setType.getInternalName(), Type.getDescriptor(MethodHeader.class)),
            null);

        mv.visitLabel(startLabel);
        mv.visitIntInsn(SIPUSH, methodHeaders.size());
        mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(Object.class));
        maxStack = stackSize = 1;
        int i = 0;
        for (MethodHeader methodHeader : methodHeaders) {
            mv.visitInsn(DUP);
            maxStack = Math.max(maxStack, ++stackSize);
            mv.visitIntInsn(SIPUSH, i++);
            maxStack = Math.max(maxStack, ++stackSize);
            int stackSizeUsed = buildMethodHeader(mv, methodHeader);
            maxStack = Math.max(maxStack, stackSize++ + stackSizeUsed);
            mv.visitInsn(AASTORE);
            stackSize -= 3;
        }
        mv.visitMethodInsn(INVOKESTATIC,
            setType.getInternalName(),
            "of",
            Type.getMethodDescriptor(setType, Type.getType(Object[].class)),
            true);
        mv.visitInsn(ARETURN);
        mv.visitLabel(endLabel);
        mv.visitLocalVariable("this",
            Type.getObjectType(className).getDescriptor(),
            null,
            startLabel,
            endLabel,
            0);
        mv.visitMaxs(maxStack, 1);
    }
}
