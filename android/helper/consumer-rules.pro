# The daemon and the uninstall script launch the helpers by name with app_process.
-keep class li.mercury.bigbrother.catalog.AppOpsGuard {
    public static void main(java.lang.String[]);
}
-keep class li.mercury.bigbrother.catalog.AppOpsDump {
    public static void main(java.lang.String[]);
}
-keep class li.mercury.bigbrother.catalog.BinderCatalog {
    public static void main(java.lang.String[]);
}
