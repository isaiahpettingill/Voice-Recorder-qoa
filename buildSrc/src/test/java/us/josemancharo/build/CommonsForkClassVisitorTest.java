package us.josemancharo.build;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.*;

public class CommonsForkClassVisitorTest {
    private byte[] fixture(String className, Object... constants) {
        return fixtureMethod(className, "onCreate", "(Landroid/os/Bundle;)V", constants);
    }
    private byte[] fixtureMethod(String className, String name, String descriptor, Object... constants) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        for (Object value : constants) { method.visitLdcInsn(value); method.visitInsn(Opcodes.POP); }
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private byte[] transform(byte[] input) {
        ClassWriter out = new ClassWriter(0);
        new ClassReader(input).accept(new CommonsForkClassVisitor(out), 0);
        return out.toByteArray();
    }
    private List<Object> constants(byte[] bytes) {
        List<Object> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) { result.add(value); }
                };
            }
        }, 0);
        return result;
    }
    @Test public void mapsOnlyTheTwoExactIdentityLiterals() {
        assertEquals(Arrays.asList("us.josemancharo.voicerecorderqoa", "yfissof", "org.fossify.commons", "android.permission.RECORD_AUDIO", 100),
            constants(transform(fixture(CommonsForkClassVisitor.TARGET, "org.fossify.", "yfissof", "org.fossify.commons", "android.permission.RECORD_AUDIO", 100))));
    }
    @Test public void customizationChangesOnlyItsExactIdentityLiteral() {
        assertEquals(Arrays.asList("orahcnamesoj", "org.fossify.", "android.permission.RECORD_AUDIO"),
            constants(transform(fixtureMethod(CommonsForkClassVisitor.TARGET, "startCustomizationActivity", "()V", "yfissof", "org.fossify.", "android.permission.RECORD_AUDIO"))));
    }
    @Test public void otherTargetMethodsRemainUnchanged() {
        byte[] before = fixtureMethod(CommonsForkClassVisitor.TARGET, "getExportSettingsFilename", "()Ljava/lang/String;", "org.fossify.", "yfissof");
        assertArrayEquals(before, transform(before));
    }
    @Test public void unrelatedClassesRemainUnchanged() {
        byte[] before = fixture("org.fossify.commons.activities.OtherActivity", "org.fossify.", "yfissof", "android.permission.RECORD_AUDIO");
        assertArrayEquals(before, transform(before));
    }
    @Test public void nestedClassesRemainUnchanged() {
        byte[] before = fixture(CommonsForkClassVisitor.TARGET + "$Companion", "org.fossify.", "yfissof");
        assertArrayEquals(before, transform(before));
    }
    @Test public void alreadyAdaptedValuesRemainUnchanged() {
        byte[] before = fixture(CommonsForkClassVisitor.TARGET, "us.josemancharo.voicerecorderqoa", "orahcnamesoj");
        assertArrayEquals(before, transform(before));
    }
}
