# The Xposed API is compileOnly: the framework provides it when it loads the
# hook, so its classes are missing at build time.
-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.api.**

# Keep the hook package as written, members included.
#
# LSPosed loads the entry class by the name listed in
# META-INF/xposed/java_init.list. The libxposed project suggests letting it be
# renamed and rewriting the list with -adaptresourcefilecontents, but the list
# is a Java resource of this library module and R8 does not rewrite it when
# the app is built.
#
# The members must be kept too. The classes here implement interfaces of the
# Xposed API (XposedModule, XposedInterface.Hooker); with those missing, R8
# cannot see that onPackageReady() or a hooker's intercept() implement
# framework methods. It strips or renames them, and the framework then fails
# with AbstractMethodError.
-keep class li.mercury.bigbrother.hook.** {
    *;
}
