# Stack traces in the app's log keep file names and line numbers; the
# mapping saved with each release (build/outputs/mapping/release) turns the
# rest back into names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
