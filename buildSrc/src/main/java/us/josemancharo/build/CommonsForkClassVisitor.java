package us.josemancharo.build;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Adapts two upstream branding identity literals, not permissions or safety checks. */
public final class CommonsForkClassVisitor extends ClassVisitor {
    public static final String TARGET = "org.fossify.commons.activities.BaseSimpleActivity";
    private boolean target;

    public CommonsForkClassVisitor(ClassVisitor next) { super(Opcodes.ASM9, next); }

    @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        target = TARGET.equals(name.replace('/', '.'));
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
        final boolean creation = "onCreate".equals(name) && "(Landroid/os/Bundle;)V".equals(descriptor);
        final boolean customization = "startCustomizationActivity".equals(name) && "()V".equals(descriptor);
        if (!target || next == null || (!creation && !customization)) return next;
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override public void visitLdcInsn(Object value) {
                if (creation && "org.fossify.".equals(value)) value = "us.josemancharo.voicerecorderqoa";
                else if (customization && "yfissof".equals(value)) value = "orahcnamesoj";
                super.visitLdcInsn(value);
            }
        };
    }
}
