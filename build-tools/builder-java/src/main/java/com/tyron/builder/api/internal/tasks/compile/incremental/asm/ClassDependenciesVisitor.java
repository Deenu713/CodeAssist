package com.tyron.builder.api.internal.tasks.compile.incremental.asm;


import com.tyron.builder.api.internal.tasks.compile.incremental.deps.ClassAnalysis;
import com.tyron.builder.internal.cache.StringInterner;
import com.tyron.builder.internal.classanalysis.AsmConstants;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;


public class ClassDependenciesVisitor extends ClassVisitor {

    private final static int API = AsmConstants.ASM_LEVEL;

    private final IntSet constants;
    private final Set<String> privateTypes;
    private final Set<String> accessibleTypes;
    private final Predicate<String> typeFilter;
    private final StringInterner interner;
    private boolean isAnnotationType;
    private String dependencyToAllReason;
    private String moduleName;
    private final RetentionPolicyVisitor retentionPolicyVisitor;

    private ClassDependenciesVisitor(Predicate<String> typeFilter, ClassReader reader, StringInterner interner) {
        super(API);
        this.constants = new IntOpenHashSet(2);
        this.privateTypes = new HashSet<>();
        this.accessibleTypes = new HashSet<>();
        this.retentionPolicyVisitor = new RetentionPolicyVisitor();
        this.typeFilter = typeFilter;
        this.interner = interner;
        collectRemainingClassDependencies(reader);
    }

    public static ClassAnalysis analyze(String className, ClassReader reader, StringInterner interner) {
        ClassDependenciesVisitor visitor = new ClassDependenciesVisitor(new ClassRelevancyFilter(className), reader, interner);
        reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // Remove the "API accessible" types from the "privately used types"
        visitor.privateTypes.removeAll(visitor.accessibleTypes);
        String name = visitor.moduleName != null ? visitor.moduleName : className;
        return new ClassAnalysis(interner.intern(name), visitor.getPrivateClassDependencies(), visitor.getAccessibleClassDependencies(), visitor.getDependencyToAllReason(), visitor.getConstants());
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        isAnnotationType = isAnnotationType(interfaces);
        Set<String> types = isAccessible(access) ? accessibleTypes : privateTypes;
        if (superName != null) {
            // superName can be null if what we are analyzing is `java.lang.Object`
            // which can happen when a custom Java SDK is on classpath (typically, android.jar)
            Type type = Type.getObjectType(superName);
            maybeAddDependentType(types, type);
        }
        for (String s : interfaces) {
            Type interfaceType = Type.getObjectType(s);
            maybeAddDependentType(types, interfaceType);
        }
    }

    @Override
    public ModuleVisitor visitModule(String name, int access, String version) {
        moduleName = name;
        dependencyToAllReason = "module-info of '" + name + "' has changed";
        return null;
    }

    // performs a fast analysis of classes referenced in bytecode (method bodies)
    // avoiding us to implement a costly visitor and potentially missing edge cases
    private void collectRemainingClassDependencies(ClassReader reader) {
        char[] charBuffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int itemOffset = reader.getItem(i);
            // see https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.4
            if (itemOffset > 0 && reader.readByte(itemOffset - 1) == 7) {
                // A CONSTANT_Class entry, read the class descriptor
                String classDescriptor = reader.readUTF8(itemOffset, charBuffer);
                Type type = Type.getObjectType(classDescriptor);
                maybeAddDependentType(privateTypes, type);
            }
        }
    }

    protected void maybeAddDependentType(Set<String> types, Type type) {
        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        if (type.getSort() != Type.OBJECT) {
            return;
        }
        String name = type.getClassName();
        if (typeFilter.test(name)) {
            types.add(interner.intern(name));
        }
    }

    public Set<String> getPrivateClassDependencies() {
        return privateTypes;
    }

    public Set<String> getAccessibleClassDependencies() {
        return accessibleTypes;
    }

    public IntSet getConstants() {
        return constants;
    }

    private boolean isAnnotationType(String[] interfaces) {
        return interfaces.length == 1 && interfaces[0].equals("java/lang/annotation/Annotation");
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        Set<String> types = isAccessible(access) ? accessibleTypes : privateTypes;
        maybeAddDependentType(types, Type.getType(desc));
        if (isAccessibleConstant(access, value)) {
            // we need to compute a hash for a constant, which is based on the name of the constant + its value
            // otherwise we miss the case where a class defines several constants with the same value, or when
            // two values are switched
            constants.add((name + '|' + value).hashCode()); //non-private const
        }
        return new FieldVisitor(types);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        Set<String> types = isAccessible(access) ? accessibleTypes : privateTypes;
        Type methodType = Type.getMethodType(desc);
        maybeAddDependentType(types, methodType.getReturnType());
        for (Type argType : methodType.getArgumentTypes()) {
            maybeAddDependentType(types, argType);
        }
        return new MethodVisitor(types);
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (isAnnotationType && "Ljava/lang/annotation/Retention;".equals(desc)) {
            return retentionPolicyVisitor;
        } else {
            maybeAddDependentType(accessibleTypes, Type.getType(desc));
            return new AnnotationVisitor(accessibleTypes);
        }
    }

    private static boolean isAccessible(int access) {
        return (access & Opcodes.ACC_PRIVATE) == 0;
    }

    private static boolean isAccessibleConstant(int access, Object value) {
        return isConstant(access) && isAccessible(access) && value != null;
    }

    private static boolean isConstant(int access) {
        return (access & Opcodes.ACC_FINAL) != 0 && (access & Opcodes.ACC_STATIC) != 0;
    }

    public String getDependencyToAllReason() {
        return dependencyToAllReason;
    }

    private class FieldVisitor extends org.objectweb.asm.FieldVisitor {
        private final Set<String> types;

        public FieldVisitor(Set<String> types) {
            super(API);
            this.types = types;
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            maybeAddDependentType(types, Type.getType(descriptor));
            return new AnnotationVisitor(types);
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            maybeAddDependentType(types, Type.getType(descriptor));
            return new AnnotationVisitor(types);
        }
    }

    private class MethodVisitor extends org.objectweb.asm.MethodVisitor {
        private final Set<String> types;

        protected MethodVisitor(Set<String> types) {
            super(API);
            this.types = types;
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            maybeAddDependentType(types, Type.getType(desc));
            super.visitLocalVariable(name, desc, signature, start, end, index);
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            maybeAddDependentType(types, Type.getType(descriptor));
            return new AnnotationVisitor(types);
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            maybeAddDependentType(types, Type.getType(descriptor));
            return new AnnotationVisitor(types);
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            maybeAddDependentType(types, Type.getType(descriptor));
            return new AnnotationVisitor(types);
        }
    }

    private class RetentionPolicyVisitor extends org.objectweb.asm.AnnotationVisitor {
        public RetentionPolicyVisitor() {
            super(ClassDependenciesVisitor.API);
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            if ("Ljava/lang/annotation/RetentionPolicy;".equals(desc)) {
                RetentionPolicy policy = RetentionPolicy.valueOf(value);
                if (policy == RetentionPolicy.SOURCE) {
                    dependencyToAllReason = "source retention annotation '" + name + "' has changed";
                }
            }
        }
    }

    private class AnnotationVisitor extends org.objectweb.asm.AnnotationVisitor {
        private final Set<String> types;

        public AnnotationVisitor(Set<String> types) {
            super(ClassDependenciesVisitor.API);
            this.types = types;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type) {
                maybeAddDependentType(types, (Type) value);
            }
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
            return this;
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String name, String descriptor) {
            maybeAddDependentType(types, Type.getType(descriptor));
            return this;
        }
    }
}
