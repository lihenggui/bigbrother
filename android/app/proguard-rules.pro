# Stack traces in the app's log keep file names and line numbers; the
# mapping saved with each release (build/outputs/mapping/release) turns the
# rest back into names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Run by the daemon through app_process, by name; nothing in the app refers
# to them.
-keep class li.mercury.bigbrother.catalog.* {
    public static void main(java.lang.String[]);
}
