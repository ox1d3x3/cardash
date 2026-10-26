import java.io.*;
import java.nio.file.*;
import java.util.stream.*;

/**
 * Local-build helper only. JDK 21 javac emits MethodParameters entries with no name for
 * inner-class constructors; the D8 bundled in bundletool crashes on them. This rewrites
 * every .class under a directory with method-level MethodParameters attributes removed
 * (they are debug metadata; behaviour is unchanged). Gradle builds don't need this.
 */
public class StripParams {
    public static void main(String[] a) throws Exception {
        try (Stream<Path> s = Files.walk(Paths.get(a[0]))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".class")).collect(Collectors.toList())) {
                Files.write(p, strip(Files.readAllBytes(p)));
            }
        }
    }

    static byte[] strip(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeInt(in.readInt());          // magic
        out.writeInt(in.readInt());          // minor+major
        int cpCount = in.readUnsignedShort();
        out.writeShort(cpCount);
        String[] utf = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            out.writeByte(tag);
            switch (tag) {
                case 1: { String v = in.readUTF(); utf[i] = v; out.writeUTF(v); break; }
                case 3: case 4: copy(in, out, 4); break;
                case 5: case 6: copy(in, out, 8); i++; break;
                case 7: case 8: case 16: case 19: case 20: copy(in, out, 2); break;
                case 9: case 10: case 11: case 12: case 17: case 18: copy(in, out, 4); break;
                case 15: copy(in, out, 3); break;
                default: throw new IOException("bad cp tag " + tag);
            }
        }
        copy(in, out, 6);                    // access, this, super
        int ifc = in.readUnsignedShort();
        out.writeShort(ifc);
        copy(in, out, ifc * 2);
        for (int m = 0; m < 2; m++) {        // fields, then methods
            int n = in.readUnsignedShort();
            out.writeShort(n);
            for (int i = 0; i < n; i++) {
                copy(in, out, 6);
                attrs(in, out, utf, m == 1);
            }
        }
        attrs(in, out, utf, false);          // class attributes
        return bo.toByteArray();
    }

    static void attrs(DataInputStream in, DataOutputStream out, String[] utf, boolean dropParams) throws IOException {
        int n = in.readUnsignedShort();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream tmp = new DataOutputStream(bo);
        int kept = 0;
        for (int i = 0; i < n; i++) {
            int name = in.readUnsignedShort();
            int len = in.readInt();
            byte[] data = new byte[len];
            in.readFully(data);
            if (dropParams && "MethodParameters".equals(utf[name])) continue;
            tmp.writeShort(name);
            tmp.writeInt(len);
            tmp.write(data);
            kept++;
        }
        out.writeShort(kept);
        out.write(bo.toByteArray());
    }

    static void copy(DataInputStream in, DataOutputStream out, int n) throws IOException {
        byte[] buf = new byte[n];
        in.readFully(buf);
        out.write(buf);
    }
}
