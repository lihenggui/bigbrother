# Stack traces in the app's log keep line numbers and replace file names
# with "SourceFile". The release mapping (build/outputs/mapping/release)
# restores the original names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
