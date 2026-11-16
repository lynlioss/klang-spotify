import java.io.File;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.baksmali.Baksmali;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import java.io.StringWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Invoke the disassembler API retained in Apktool's packaged, optimized jar. */
public class SmaliDump {
    public static void main(String[] args) throws Exception {
        var container = DexFileFactory.loadDexContainer(new File(args[0]), new Opcodes(37, -1));
        var options = new BaksmaliOptions();
        options.debugInfo = true;
        options.localsDirective = true;
        try (var zip = new ZipOutputStream(new FileOutputStream(args[1]))) {
            for (Object name : container.getDexEntryNames()) {
                var dex = (DexBackedDexFile) container.getEntry((String) name).getDexFile();
                for (int i = 0; i < dex.classSection.size(); i++) {
                    var cls = (ClassDef) dex.classSection.get(i);
                    var text = new StringWriter();
                    var writer = new BaksmaliWriter(text);
                    new ClassDefinition(options, cls).writeTo(writer);
                    writer.flush();
                    String descriptor = cls.getType();
                    zip.putNextEntry(new ZipEntry(descriptor.substring(1, descriptor.length()-1) + ".smali"));
                    zip.write(text.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        }
    }
}
