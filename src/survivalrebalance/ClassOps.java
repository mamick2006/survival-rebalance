package survivalrebalance;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Reading, fingerprinting and patching constants inside compiled methods. */
final class ClassOps {
    private ClassOps() {}

    static final class Const {
        final AbstractInsnNode insn;
        final String type;   // int long float double string recipe
        final Object value;
        final int bsmIndex;  // for indy constants, which bootstrap argument; else -1
        Const(AbstractInsnNode insn, String type, Object value, int bsmIndex) {
            this.insn = insn; this.type = type; this.value = value; this.bsmIndex = bsmIndex;
        }
    }

    static ClassNode read(byte[] bytes, int flags) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, flags);
        return cn;
    }

    static MethodNode findMethod(ClassNode cn, String name, String desc) throws Failure {
        List<MethodNode> hits = new ArrayList<>();
        for (MethodNode m : cn.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) hits.add(m);
        if (hits.isEmpty()) {
            if (desc != null) throw new Failure("method " + name + desc + " not found in " + cn.name);
            throw new Failure("method " + name + " not found in " + cn.name);
        }
        if (hits.size() > 1) {
            StringBuilder b = new StringBuilder();
            for (MethodNode m : hits) b.append(' ').append(m.desc);
            throw new Failure("method " + name + " is overloaded in " + cn.name + ", give \"desc\", one of:" + b);
        }
        return hits.get(0);
    }

    static List<Const> constants(MethodNode m) {
        List<Const> out = new ArrayList<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            int op = in.getOpcode();
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) out.add(new Const(in, "int", op - Opcodes.ICONST_0, -1));
            else if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) out.add(new Const(in, "long", (long) (op - Opcodes.LCONST_0), -1));
            else if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) out.add(new Const(in, "float", (float) (op - Opcodes.FCONST_0), -1));
            else if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) out.add(new Const(in, "double", (double) (op - Opcodes.DCONST_0), -1));
            else if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) out.add(new Const(in, "int", ((IntInsnNode) in).operand, -1));
            else if (op == Opcodes.LDC) {
                Object c = ((LdcInsnNode) in).cst;
                if (c instanceof Integer) out.add(new Const(in, "int", c, -1));
                else if (c instanceof Long) out.add(new Const(in, "long", c, -1));
                else if (c instanceof Float) out.add(new Const(in, "float", c, -1));
                else if (c instanceof Double) out.add(new Const(in, "double", c, -1));
                else if (c instanceof String) out.add(new Const(in, "string", c, -1));
            } else if (op == Opcodes.INVOKEDYNAMIC) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) in;
                boolean concat = indy.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory");
                for (int k = 0; k < indy.bsmArgs.length; k++) {
                    if (indy.bsmArgs[k] instanceof String)
                        out.add(new Const(in, concat && k == 0 ? "recipe" : "string", indy.bsmArgs[k], k));
                }
            }
        }
        return out;
    }

    static boolean numEquals(String type, Object value, double from) {
        switch (type) {
            case "int": return value instanceof Integer && from == Math.rint(from) && ((Integer) value) == (long) from;
            case "long": return value instanceof Long && from == Math.rint(from) && ((Long) value) == (long) from;
            case "float": return value instanceof Float && Float.floatToIntBits((Float) value) == Float.floatToIntBits((float) from);
            case "double": return value instanceof Double && Double.doubleToLongBits((Double) value) == Double.doubleToLongBits(from);
            default: return false;
        }
    }

    /** Hash of the method's instructions and constants, ignoring line numbers, local names and frames. */
    static String fingerprint(MethodNode m) {
        StringBuilder b = new StringBuilder();
        b.append(m.name).append(m.desc).append('|');
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext())
            if (in instanceof LabelNode) labels.put((LabelNode) in, labels.size());
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof LineNumberNode || in instanceof FrameNode) continue;
            if (in instanceof LabelNode) { b.append("L").append(labels.get(in)).append(';'); continue; }
            b.append(in.getOpcode());
            if (in instanceof IntInsnNode) b.append(' ').append(((IntInsnNode) in).operand);
            else if (in instanceof VarInsnNode) b.append(' ').append(((VarInsnNode) in).var);
            else if (in instanceof TypeInsnNode) b.append(' ').append(((TypeInsnNode) in).desc);
            else if (in instanceof FieldInsnNode) { FieldInsnNode f = (FieldInsnNode) in; b.append(' ').append(f.owner).append('.').append(f.name).append(f.desc); }
            else if (in instanceof MethodInsnNode) { MethodInsnNode f = (MethodInsnNode) in; b.append(' ').append(f.owner).append('.').append(f.name).append(f.desc).append(f.itf); }
            else if (in instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode f = (InvokeDynamicInsnNode) in;
                b.append(' ').append(f.name).append(f.desc).append(' ').append(handle(f.bsm));
                for (Object a : f.bsmArgs) b.append(' ').append(cst(a));
            } else if (in instanceof JumpInsnNode) b.append(" L").append(labels.get(((JumpInsnNode) in).label));
            else if (in instanceof LdcInsnNode) b.append(' ').append(cst(((LdcInsnNode) in).cst));
            else if (in instanceof IincInsnNode) b.append(' ').append(((IincInsnNode) in).var).append(' ').append(((IincInsnNode) in).incr);
            else if (in instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode t = (TableSwitchInsnNode) in;
                b.append(' ').append(t.min).append(' ').append(t.max).append(" L").append(labels.get(t.dflt));
                for (LabelNode l : t.labels) b.append(" L").append(labels.get(l));
            } else if (in instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode t = (LookupSwitchInsnNode) in;
                b.append(" L").append(labels.get(t.dflt));
                for (int i = 0; i < t.keys.size(); i++) b.append(' ').append(t.keys.get(i)).append(" L").append(labels.get(t.labels.get(i)));
            } else if (in instanceof MultiANewArrayInsnNode) b.append(' ').append(((MultiANewArrayInsnNode) in).desc).append(' ').append(((MultiANewArrayInsnNode) in).dims);
            b.append(';');
        }
        for (TryCatchBlockNode t : m.tryCatchBlocks)
            b.append("T L").append(labels.get(t.start)).append(" L").append(labels.get(t.end)).append(" L").append(labels.get(t.handler)).append(' ').append(t.type).append(';');
        return Text.sha256(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String handle(Handle h) { return h.getTag() + ":" + h.getOwner() + "." + h.getName() + h.getDesc(); }

    private static String cst(Object c) {
        if (c instanceof String) return "S" + Json.quote((String) c);
        if (c instanceof Type) return "T" + c;
        if (c instanceof Handle) return "H" + handle((Handle) c);
        if (c instanceof Float) return "F" + Float.floatToIntBits((Float) c);
        if (c instanceof Double) return "D" + Double.doubleToLongBits((Double) c);
        if (c instanceof Long) return "J" + c;
        return c.getClass().getSimpleName().charAt(0) + String.valueOf(c);
    }

    static String fingerprintOf(byte[] cls, String method, String desc) throws Failure {
        ClassNode cn = read(cls, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return fingerprint(findMethod(cn, method, desc));
    }

    /**
     * Apply every recorded constant edit to the original class in one pass. Methods are keyed
     * name+desc, constants by their ordinal in constants() of the original method. After the
     * rewrite, each edited method must read exactly its expected constants and every other
     * method must keep its fingerprint.
     */
    static byte[] rewriteAll(byte[] cls, Map<String, Map<Integer, Workspace.CEdit>> edits) throws Failure {
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(cr, 0);
        Failure[] err = new Failure[1];
        Map<String, List<Object>> expected = new HashMap<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String d, String sig, String[] exc) {
                MethodVisitor down = super.visitMethod(access, name, d, sig, exc);
                Map<Integer, Workspace.CEdit> me = edits.get(name + d);
                if (me == null) return down;
                return new MethodNode(Opcodes.ASM9, access, name, d, sig, exc) {
                    @Override
                    public void visitEnd() {
                        try {
                            List<Const> cs = constants(this);
                            List<Object> exp = new ArrayList<>();
                            for (Const c : cs) exp.add(c.value);
                            for (Map.Entry<Integer, Workspace.CEdit> e : me.entrySet()) {
                                int ord = e.getKey();
                                if (ord >= cs.size()) throw new Failure("constant " + ord + " of " + name + " does not exist");
                                Const c = cs.get(ord);
                                Workspace.CEdit ed = e.getValue();
                                if (ed.value instanceof String) {
                                    if (c.insn instanceof LdcInsnNode) ((LdcInsnNode) c.insn).cst = ed.value;
                                    else ((InvokeDynamicInsnNode) c.insn).bsmArgs[c.bsmIndex] = ed.value;
                                } else instructions.set(c.insn, pushValue(ed.type, ed.value));
                                exp.set(ord, ed.value);
                            }
                            expected.put(name + d, exp);
                        } catch (Failure f) { err[0] = f; }
                        accept(down);
                    }
                };
            }
        }, 0);
        if (err[0] != null) throw err[0];
        byte[] out = cw.toByteArray();
        ClassNode a = read(out, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        ClassNode b = read(cls, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (a.methods.size() != b.methods.size()) throw new Failure("self-check: the class lost or gained a method");
        for (int i = 0; i < a.methods.size(); i++) {
            MethodNode ma = a.methods.get(i), mb = b.methods.get(i);
            List<Object> exp = expected.get(ma.name + ma.desc);
            if (exp == null) {
                if (!fingerprint(ma).equals(fingerprint(mb))) throw new Failure("self-check: method " + ma.name + " changed although no op targets it");
                continue;
            }
            List<Const> cs = constants(ma);
            if (cs.size() != exp.size()) throw new Failure("self-check: " + ma.name + " has " + cs.size() + " constants after the edit, expected " + exp.size());
            for (int k = 0; k < cs.size(); k++)
                if (!cs.get(k).value.equals(exp.get(k))) throw new Failure("self-check: constant " + k + " of " + ma.name + " reads " + cs.get(k).value + " after the edit, expected " + exp.get(k));
        }
        return out;
    }

    /** The typed value an op's "to" stands for; throws when it does not fit the type. */
    static Object numValue(String type, java.math.BigDecimal v) throws Failure {
        try {
            switch (type) {
                case "int": return v.intValueExact();
                case "long": return v.longValueExact();
                case "float": return v.floatValue();
                case "double": return v.doubleValue();
                default: throw new Failure("\"type\" must be int, long, float or double, not " + type);
            }
        } catch (ArithmeticException e) {
            throw new Failure("\"to\" " + v.toPlainString() + " is not a " + type);
        }
    }

    static AbstractInsnNode pushValue(String type, Object v) {
        switch (type) {
            case "int": return pushInt((Integer) v);
            case "long": {
                long l = (Long) v;
                return l == 0 || l == 1 ? new InsnNode(Opcodes.LCONST_0 + (int) l) : new LdcInsnNode(l);
            }
            case "float": {
                float f = (Float) v;
                if (Float.floatToIntBits(f) == Float.floatToIntBits(0f)) return new InsnNode(Opcodes.FCONST_0);
                if (f == 1f) return new InsnNode(Opcodes.FCONST_1);
                if (f == 2f) return new InsnNode(Opcodes.FCONST_2);
                return new LdcInsnNode(f);
            }
            default: {
                double d = (Double) v;
                if (Double.doubleToLongBits(d) == Double.doubleToLongBits(0d)) return new InsnNode(Opcodes.DCONST_0);
                if (d == 1d) return new InsnNode(Opcodes.DCONST_1);
                return new LdcInsnNode(d);
            }
        }
    }

    static AbstractInsnNode pushInt(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    /** Text form of a constant for dump and messages. */
    static String show(Const c) {
        if (c.value instanceof String) return Json.quote((String) c.value);
        if (c.value instanceof Float) return Float.toString((Float) c.value) + "f";
        if (c.value instanceof Long) return c.value + "L";
        return String.valueOf(c.value);
    }

    /** dump-class output for one class. */
    static String dump(byte[] cls, String onlyMethod) {
        ClassNode cn = read(cls, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        StringBuilder b = new StringBuilder();
        b.append("class ").append(cn.name).append('\n');
        for (MethodNode m : cn.methods) {
            if (onlyMethod != null && !m.name.equals(onlyMethod)) continue;
            List<Const> cs = constants(m);
            b.append("\nmethod ").append(m.name).append(' ').append(m.desc)
              .append("   method_fp ").append(fingerprint(m)).append('\n');
            Map<String, Integer> seen = new HashMap<>();
            for (int i = 0; i < cs.size(); i++) {
                Const c = cs.get(i);
                String key = c.type.equals("recipe") ? "string" : c.type;
                String vk = key + ":" + show(c);
                int occ = seen.merge(vk, 1, Integer::sum) - 1;
                b.append(String.format("  [%3d] %-6s %s   (which %d)%s%n", i, c.type, show(c), occ,
                        c.type.equals("recipe") ? "   indy string concat recipe, \\u0001 marks an argument" : ""));
            }
            if (cs.isEmpty()) b.append("  (no constants)\n");
        }
        return b.toString();
    }
}
