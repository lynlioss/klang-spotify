-keep class com.yausername.** { *; }
-keep class org.apache.commons.io.** { public <init>(...); }
-keep class com.fasterxml.jackson.** { *; }
-keep class moe.rukamori.archivetune.spotify.models.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-dontwarn org.slf4j.impl.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
# Jackson probes these optional desktop integrations reflectively. The downloader
# only uses JSON binding; Android has neither java.beans nor DOM bootstrapping.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry

-keep,allowobfuscation class * implements org.apache.commons.compress.archivers.zip.ZipExtraField { public <init>(); }
