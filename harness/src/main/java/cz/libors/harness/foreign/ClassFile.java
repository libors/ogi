package cz.libors.harness.foreign;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a class file names: its own class, its super class and interfaces, every field and method it references, and
 * its native methods. Code in a class can only reach other classes through these references; the bytecode itself is
 * not parsed.
 */
final class ClassFile {

    private static final int MAGIC = 0xCAFEBABE;
    private static final int ACC_NATIVE = 0x0100;

    /** A field or method reference from the constant pool, with internal names (e.g. {@code java/lang/System}). */
    record MemberRef(String owner, String name, String descriptor) {
    }

    final String name;
    /** Null for {@code java/lang/Object} and module descriptors. */
    final String superName;
    final List<String> interfaces;
    final List<MemberRef> memberRefs;
    final List<String> nativeMethods;

    private ClassFile(String name, String superName, List<String> interfaces, List<MemberRef> memberRefs,
                      List<String> nativeMethods) {
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces;
        this.memberRefs = memberRefs;
        this.nativeMethods = nativeMethods;
    }

    static boolean hasMagic(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] & 0xff) == 0xCA && (bytes[1] & 0xff) == 0xFE
                && (bytes[2] & 0xff) == 0xBA && (bytes[3] & 0xff) == 0xBE;
    }

    static ClassFile parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort(); // minor version
        in.readUnsignedShort(); // major version
        int count = in.readUnsignedShort();
        int[] tags = new int[count];
        String[] utf8 = new String[count];
        int[] first = new int[count];
        int[] second = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                // Class, String, MethodType, Module, Package
                case 7, 8, 16, 19, 20 -> first[i] = in.readUnsignedShort();
                // Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
                case 9, 10, 11, 12, 17, 18 -> {
                    first[i] = in.readUnsignedShort();
                    second[i] = in.readUnsignedShort();
                }
                // MethodHandle: its target is a member reference listed on its own
                case 15 -> {
                    in.readUnsignedByte();
                    first[i] = in.readUnsignedShort();
                }
                default -> throw new IOException("unknown constant pool tag " + tag + " at " + i);
            }
        }
        List<MemberRef> memberRefs = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                int nameAndType = second[i];
                memberRefs.add(new MemberRef(utf8[first[first[i]]], utf8[first[nameAndType]],
                        utf8[second[nameAndType]]));
            }
        }
        in.readUnsignedShort(); // access flags
        String name = className(utf8, first, in.readUnsignedShort());
        String superName = className(utf8, first, in.readUnsignedShort());
        int interfaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < interfaceCount; i++) {
            interfaces.add(className(utf8, first, in.readUnsignedShort()));
        }
        skipMembers(in);
        List<String> nativeMethods = new ArrayList<>();
        int methodCount = in.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            int access = in.readUnsignedShort();
            String methodName = utf8[in.readUnsignedShort()];
            in.readUnsignedShort(); // descriptor
            skipAttributes(in);
            if ((access & ACC_NATIVE) != 0) {
                nativeMethods.add(methodName);
            }
        }
        return new ClassFile(name, superName, List.copyOf(interfaces), List.copyOf(memberRefs),
                List.copyOf(nativeMethods));
    }

    private static String className(String[] utf8, int[] first, int classIndex) {
        return classIndex == 0 ? null : utf8[first[classIndex]];
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // name
            in.readUnsignedShort(); // descriptor
            skipAttributes(in);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort(); // name
            in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }
}
